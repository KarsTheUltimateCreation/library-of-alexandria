package alexandria.core.sources.adapters

import alexandria.core.model.ContentKind
import alexandria.core.model.CoverCandidate
import alexandria.core.model.CoverOrigin
import alexandria.core.model.ExternalIds
import alexandria.core.model.PublicationStatus
import alexandria.core.model.ReaderPage
import alexandria.core.model.SourceChapter
import alexandria.core.model.SourceSeries
import alexandria.core.net.RateLimiter
import alexandria.core.net.fetchText
import alexandria.core.sources.AdapterContext
import alexandria.core.sources.ExternalLinkIds
import alexandria.core.sources.SourceAdapter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.Jsoup

/**
 * MangaFire (mangafire.to), a large aggregator with a JSON API. English chapters only.
 *
 * Every `/api/` request carries a `vrf` signature computed from its path and sorted query (three table/key
 * substitution stages, URL-safe Base64). The endpoints, the signing scheme and its constants are adapted from
 * keiyoushi/extensions-source `src/all/mangafire` (Apache-2.0); `java.util.Base64` replaces Android's so it runs on
 * any JVM host. MangaFire may answer `403 captcha_required` (a WAF check Keiyoushi solves in a WebView): that is not
 * supported here, so such a response is an ordinary failure and other providers serve the chapter.
 *
 * Series id: MangaFire's short hid ("lrmyz"). Chapter id: its numeric chapter id.
 */
class MangaFireAdapter(
    private val ctx: AdapterContext,
    override val baseUrl: String = "https://mangafire.to",
    private val clock: () -> Long = System::currentTimeMillis,
) : SourceAdapter {
    override val id = ID
    override val name = "MangaFire"
    override val languages: Set<String> = setOf("en")
    override val imageHosts = setOf("mfcdn.nl", "mfcdn1.xyz", "mfcdn2.xyz", "mfcdn.xyz")
    private val limiter = RateLimiter.shared(ID, 2, 1_000) // Keiyoushi: 2 requests / s

    @Serializable
    internal class Page<T>(val items: List<T> = emptyList(), val meta: Meta? = null)

    @Serializable
    internal class Meta(val lastPage: Int = 1, val hasNext: Boolean = false)

    @Serializable
    internal class Data<T>(val data: T)

    @Serializable
    internal class Poster(val small: String? = null, val medium: String? = null, val large: String? = null)

    @Serializable
    internal class Entity(val title: String)

    @Serializable
    internal class Title(
        val hid: String,
        val slug: String? = null,
        val title: String,
        val type: String? = null,
        val status: String? = null,
        val poster: Poster? = null,
        val year: JsonElement? = null,
        val synopsisHtml: String? = null,
        val altTitles: List<String> = emptyList(),
        val authors: List<Entity> = emptyList(),
        val artists: List<Entity> = emptyList(),
        val genres: List<Entity> = emptyList(),
        val links: Map<String, String> = emptyMap(),
        val malId: JsonElement? = null,
        val anilistId: JsonElement? = null,
        val mangabakaId: JsonElement? = null,
    )

    @Serializable
    internal class Chapter(val id: Long, val number: Double, val name: String? = null, val language: String? = null, val type: String? = null, val createdAt: Long? = null)

    @Serializable
    internal class Pages(val pages: List<PageUrl> = emptyList())

    @Serializable
    internal class PageUrl(val url: String)

    private suspend fun api(path: String, params: List<Pair<String, String>> = emptyList()): String {
        val request = Request.Builder().url(signedUrl(path, params))
            .header("User-Agent", ctx.browserUserAgent)
            .header("Accept", "application/json")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", "$baseUrl/")
            .build()
        return ctx.client.fetchText(request, limiter = limiter)
    }

    /** `$baseUrl/api$path?<sorted params>&vrf=<signature>`. */
    internal fun signedUrl(path: String, params: List<Pair<String, String>>): HttpUrl {
        val sorted = params.sortedBy { it.first }
        val signed = buildString {
            append(path)
            if (sorted.isNotEmpty()) {
                append('?')
                var lastKey = ""
                var index = 0
                append(
                    sorted.joinToString("&") { (k, v) ->
                        // Array keys are signed with their index: "languages[]" -> "languages[0]".
                        val key = if (k.endsWith("[]")) {
                            if (lastKey != k) index = 0
                            lastKey = k
                            k.replace("[]", "[${index++}]")
                        } else {
                            k
                        }
                        "$key=$v"
                    },
                )
            }
        }
        return "$baseUrl/api$path".toHttpUrl().newBuilder().apply {
            sorted.forEach { (k, v) -> addQueryParameter(k, v) }
            addQueryParameter("vrf", sign(signed))
        }.build()
    }

    override suspend fun search(query: String): List<SourceSeries> {
        if (query.isBlank()) return emptyList()
        return parseTitles(api("/titles", listOf("keyword" to query.trim(), "languages[]" to "en", "page" to "1", "limit" to "30")))
    }

    override suspend fun popular(page: Int) = parseTitles(api("/titles", listOf("order[views_30d]" to "desc", "languages[]" to "en", "page" to page.toString(), "limit" to "30")))

    override suspend fun latest(page: Int) = parseTitles(api("/titles", listOf("order[chapter_updated_at]" to "desc", "languages[]" to "en", "page" to page.toString(), "limit" to "30")))

    internal fun parseTitles(json: String): List<SourceSeries> =
        ctx.json.decodeFromString<Page<Title>>(json).items.map { it.toSeries() }.filter { it.kind != ContentKind.NOVEL }

    override suspend fun details(series: SourceSeries): SourceSeries {
        if (series.detailed) return series
        val fresh = parseDetails(api("/titles/${series.id}"))
        return fresh.copy(altTitles = (fresh.altTitles + series.altTitles).distinct())
    }

    internal fun parseDetails(json: String): SourceSeries = ctx.json.decodeFromString<Data<Title>>(json).data.toSeries(detailed = true)

    /** Last complete chapter list per series (newest first), so a refresh can read page 1 only. In memory, bounded. */
    private val fullLists = alexandria.core.cache.BoundedCache<String, Pair<Long, List<Chapter>>>(64, clock = clock)

    /**
     * The API returns at most 200 entries per page, newest number first; long series take a dozen pages (One Piece:
     * 2,445 English entries). New chapters always land on page 1, so a refresh within a day of a full read fetches
     * page 1 only and merges it with that list; once a day (and after a restart) the whole list is read again, which
     * also drops chapters the site removed.
     */
    override suspend fun chapters(series: SourceSeries, languages: List<String>): List<SourceChapter> {
        suspend fun page(n: Int) = ctx.json.decodeFromString<Page<Chapter>>(
            api("/titles/${series.id}/chapters", listOf("language" to "en", "sort" to "number", "order" to "desc", "page" to n.toString(), "limit" to "200")),
        )
        val first = page(1)
        val known = fullLists[series.id]?.takeIf { clock() - it.first < FULL_REFRESH_MS }?.second
        if (known != null && first.meta?.hasNext == true) {
            val ids = known.map { it.id }.toSet()
            // Page 1 reaches into what is known: nothing can be missing in between.
            if (first.items.any { it.id in ids }) {
                val fresh = first.items.map { it.id }.toSet()
                return parseChapters(series.id, first.items + known.filter { it.id !in fresh })
            }
        }
        val all = first.items.toMutableList()
        var n = 2
        var res = first
        while (res.meta?.hasNext == true && n <= MAX_CHAPTER_PAGES) {
            res = page(n++)
            all += res.items
        }
        fullLists[series.id] = clock() to all
        return parseChapters(series.id, all)
    }

    internal fun parseChapters(seriesId: String, list: List<Chapter>): List<SourceChapter> = list
        .filter { (it.language ?: "en").equals("en", true) }
        .map { c ->
            val n = c.number.toString().removeSuffix(".0")
            val name = c.name?.trim().orEmpty()
            // "Side Story 21" filed under number 200 is its own chapter, not a second copy of chapter 200.
            val ownNumbering = name.isNotEmpty() && OWN_NUMBERING.containsMatchIn(name)
            SourceChapter(
                sourceId = ID, seriesId = seriesId, id = c.id.toString(),
                label = when {
                    name.isEmpty() -> "Chapter $n"
                    ownNumbering -> name
                    else -> "Chapter $n: $name"
                },
                number = n.takeUnless { ownNumbering },
                title = name.takeIf { it.isNotEmpty() && !ownNumbering },
                language = "en",
                group = c.type?.replaceFirstChar { it.uppercase() },
                official = c.type?.equals("official", true),
                uploadedAt = c.createdAt?.times(1000),
                webUrl = "$baseUrl/read/$seriesId/en/chapter-$n",
            )
        }

    override suspend fun pages(chapter: SourceChapter, dataSaver: Boolean): List<ReaderPage> = parsePages(api("/chapters/${chapter.id}"))

    internal fun parsePages(json: String): List<ReaderPage> {
        val pages = ctx.json.decodeFromString<Data<Pages>>(json).data.pages
        check(pages.isNotEmpty()) { "MangaFire returned no pages" }
        return pages.mapIndexed { i, p -> ReaderPage(i, p.url, imageHeaders(p.url)) }
    }

    override suspend fun covers(series: SourceSeries): List<CoverCandidate> =
        listOfNotNull(series.coverUrl?.let { CoverCandidate(it, id, CoverOrigin.PROVIDER_ORIGINAL.name, headers = imageHeaders(it)) })

    override fun imageHeaders(imageUrl: String) = mapOf("Referer" to "$baseUrl/", "User-Agent" to ctx.browserUserAgent)

    override fun seriesWebUrl(seriesId: String) = "$baseUrl/title/$seriesId"

    private fun Title.toSeries(detailed: Boolean = false): SourceSeries {
        fun num(e: JsonElement?) = (e as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
        val ids = ExternalLinkIds.from(links.values).merge(ExternalIds(anilist = num(anilistId), mal = num(malId), mangabaka = num(mangabakaId)))
        return SourceSeries(
            sourceId = ID, id = hid, title = title.trim(),
            altTitles = altTitles.map { it.trim() }.filter { it.isNotEmpty() && !it.equals(title, true) },
            // The un-suffixed poster is the original upload (the "@280" / "@100" variants are resized copies).
            coverUrl = poster?.large ?: poster?.medium ?: poster?.small,
            ids = ids,
            kind = ContentKind.parse(type),
            status = when (status?.lowercase()) {
                "releasing" -> PublicationStatus.ONGOING
                "finished" -> PublicationStatus.COMPLETED
                "on_hiatus" -> PublicationStatus.HIATUS
                "discontinued" -> PublicationStatus.CANCELLED
                else -> PublicationStatus.UNKNOWN
            },
            year = num(year)?.toInt()?.takeIf { it in 1900..2100 },
            description = synopsisHtml?.let { Jsoup.parseBodyFragment(it).text().trim() }?.ifEmpty { null },
            authors = (authors + artists).map { it.title.trim() }.filter { it.isNotEmpty() }.distinct(),
            genres = genres.map { it.title },
            webUrl = "$baseUrl/title/$hid${slug?.let { "-$it" } ?: ""}",
            detailed = detailed,
        )
    }

    companion object {
        const val ID = "loa_mangafire"
        private const val MAX_CHAPTER_PAGES = 15
        private const val FULL_REFRESH_MS = 24 * 60 * 60_000L
        private val OWN_NUMBERING = Regex("""^(?:side\s*story|extra|special|bonus|prologue|epilogue|omake|gaiden)\b""", RegexOption.IGNORE_CASE)

        private val STAGES: List<Triple<ByteArray, ByteArray, Int>> by lazy {
            val d = java.util.Base64.getDecoder()
            listOf(
                Triple(d.decode("yINlmUNho8VYJT+ibTIP+9ESiULpVEtMOoD6U6lRE0R/xwXo/Xp9NrUgC4cw/Lmo33vUyjUE40kUoEWIr/fxfNNcq2s79ShQ5NhNrFnJ4hXPwOu/SuXzIbuTQKGFvfm08E9jvCfqAtoDqvQq3dVWPQFmJjgvkISBeXY3BgANR+yVnjGbcxZ47d6kLNfZPIayTq3/YGySb1KuVZodWp/WGNAO5pfMcpaK53Hhs0allBszaMaxuouOwdxbwgxIw6YunSsXjI05Yi0j9j4eHKfSXR8Ifo/Od+8iamRfCXTyvm7NGRGYdcQ0ywcK/u6RXhrbcCm4t2eCtrDgQVecJGkQ+A=="), d.decode("0Ec58JOY3uBzJK9m3zqIOpdlF7UFiax9DmA="), 0x5A),
                Triple(d.decode("IUFltCxD3Oc2cwCgkJffthaOg9cgPUb0LgW6H/VtfcF0kc5F25t+aWj6JH9VOhOaY0rAFdUxlDnl5BLNvwEJvQtP5qcw7vdb/K+chnbwnspSHT8mz5lqwz41TezG0hkO06FTjJZhsyNuFLDpD2ZZxQj/QIRcF90zpmQ7Byu483WsQqUE0C342HL+JXngRB6fRzxRyVTaKu83h7UYTJ0QMt6ixFh6S3F8gqkKwrGTL3jHNBsD45UnifK8+RGtishQV2K3rujLKEkiZxpr2dYcudFW4oFsDKhad3CLBvuyTqsCo4B7mL5IKQ1vXo/MOOvq1I1d8ar9X6Ttu5KF4fZgiA=="), d.decode("AAdjb1iPY8CiDmq9H34tKTBF8a3oDQ=="), 0x35),
                Triple(d.decode("NQHlu1/wVO5EmkwQymF810qqY2xG1k2obcas4Z9mCsPEIFl9pRIjFxbJ7ybMHbBckT5Ton85E0FOeHezbh/mjlEYpmpnlXOS8dgrqeq2KfxImTh1YK9y0PeMNhzA1OQzSY9brYOJq/l2QnE/hwOeZIhPixVSKIUlDb5vLcH6RWKxkIEMuP0bDwIqQ71AJJaEaMJL7A6YtyIwoRT+L5v4aZzodN/0+3nOGsfblFjgxSfPzVDjNFeNl5P26+kEC/8AHgdrpAbt3hHz3HrRN1Y6e+JHgF7ncFWnoF0y3THL1S71WgWGCa6KtSzTCCG58n68nTyj2T3Sshk7utqCtMi/ZQ=="), d.decode("DELOJgPsVaCcblDtTGMdHzM="), 0xBA),
            )
        }

        internal fun sign(path: String): String {
            var data = path.toByteArray(Charsets.UTF_8)
            for ((table, key, iv) in STAGES) {
                val out = ByteArray(data.size)
                var prev = iv
                for (i in data.indices) {
                    prev = table[(data[i].toInt() xor key[i % key.size].toInt() xor prev) and 0xFF].toInt() and 0xFF
                    out[i] = prev.toByte()
                }
                data = out
            }
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(data)
        }
    }
}
