package alexandria.core.sources.adapters

import alexandria.core.model.ContentKind
import alexandria.core.model.PublicationStatus
import alexandria.core.model.ReaderPage
import alexandria.core.model.SourceChapter
import alexandria.core.model.SourceSeries
import alexandria.core.net.RateLimiter
import alexandria.core.net.fetch
import alexandria.core.net.fetchText
import alexandria.core.sources.AdapterContext
import alexandria.core.sources.ProviderRole
import alexandria.core.sources.SourceAdapter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import okhttp3.Request
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Hitomi.la (doujinshi, manga, artist CG), English galleries only. Hitomi has no search API: the site searches a
 * static B-tree index with HTTP range requests in the browser, which this adapter reimplements
 * (`searchlib.js` / `search.js` on ltn.gold-usergeneratedcontent.net):
 *
 *  - each search word is hashed (first 4 bytes of SHA-256) and looked up in `galleriesindex/galleries.<v>.index`
 *    (464-byte nodes, 16 keys); the hit points at a list of gallery ids in `galleries.<v>.data`;
 *  - lists for all words are intersected, then with `n/index-english.nozomi` (all English galleries);
 *  - gallery details come from `galleries/<id>.js`; image URLs are derived from the file hash and `gg.js`, which
 *    changes regularly and is re-read every 30 minutes.
 *
 * Every request to Hitomi's CDNs needs the `https://hitomi.la/` Referer.
 */
class HitomiAdapter(private val ctx: AdapterContext, override val baseUrl: String = "https://hitomi.la") : SourceAdapter {
    override val id = ID
    override val name = "Hitomi"
    override val languages: Set<String> = setOf("en")
    override val adult = true
    override val role = ProviderRole.GALLERY
    override val imageHosts = setOf(DOMAIN)
    private val limiter = RateLimiter.shared("hitomi", 20, 1_000)
    private val lock = Mutex()

    private class Gg(val cases: Set<Int>, val inCase: Int, val default: Int, val b: String, val at: Long) {
        fun m(g: Int) = if (g in cases) inCase else default
    }

    @Volatile private var gg: Gg? = null
    @Volatile private var english: Pair<Long, IntArray>? = null
    @Volatile private var indexVersion: Pair<Long, String>? = null

    @Serializable
    private class File(val hash: String, val name: String? = null, val hasavif: Int? = null)

    @Serializable
    private class Artist(val artist: String)

    @Serializable
    private class Group(val group: String)

    @Serializable
    private class Parody(val parody: String)

    @Serializable
    private class Tag(val tag: String, val male: String? = null, val female: String? = null)

    @Serializable
    private class Info(
        val id: String,
        val title: String,
        val japanese_title: String? = null,
        val language: String? = null,
        val type: String? = null,
        val date: String? = null,
        val artists: List<Artist>? = null,
        val groups: List<Group>? = null,
        val parodys: List<Parody>? = null,
        val tags: List<Tag>? = null,
        val files: List<File> = emptyList(),
    )

    private fun request(url: String, range: LongRange? = null) = Request.Builder().url(url)
        .header("User-Agent", ctx.browserUserAgent)
        .header("Referer", "$baseUrl/")
        .apply { range?.let { header("Range", "bytes=${it.first}-${it.last}") } }
        .build()

    /**
     * The host client allows 5 parallel requests per host; a search here is dozens of small requests to one CDN
     * (index nodes for every word, then gallery details), so it gets its own dispatcher (same connection pool).
     */
    private val client by lazy {
        ctx.client.newBuilder().dispatcher(okhttp3.Dispatcher().apply { maxRequests = 32; maxRequestsPerHost = 16 }).build()
    }

    private suspend fun bytes(url: String, range: LongRange? = null): ByteArray =
        client.fetch(request(url, range), limiter = limiter).use { it.body.bytes() }

    private suspend fun text(url: String) = client.fetchText(request(url), limiter = limiter)

    /** Index nodes by "version@address": the upper levels are shared by every word of every search. */
    private val nodes = object : LinkedHashMap<String, Node>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Node>?) = size > 2_000
    }

    // ------------------------------------------------------------------ search

    override suspend fun search(query: String): List<SourceSeries> {
        val words = query.lowercase().replace('_', ' ').split(Regex("""\s+""")).map { it.trim() }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val (lists, englishIds) = coroutineScope {
            val l = words.distinct().map { w -> async { galleryIdsFor(w) } }
            val e = async { englishIds() }
            l.awaitAll() to e.await()
        }
        val known = lists.filterNotNull()
        if (known.isEmpty()) return emptyList()
        var ids: Set<Int> = known.minBy { it.size }.toSet()
        for (l in known) ids = ids intersect l.toSet()
        val englishSet = englishIds.toHashSet()
        // Newest first, like the site; only the first results are worth a details request each.
        val ordered = known.minBy { it.size }.filter { it in ids && it in englishSet }.sortedDescending().take(MAX_RESULTS)
        return coroutineScope { ordered.map { gid -> async { runCatching { info(gid.toString()) }.getOrNull() } }.awaitAll() }
            .filterNotNull().filter { it.language == null || it.language == "english" }.map { it.toSeries(detailed = false) }
    }

    override suspend fun latest(page: Int): List<SourceSeries> {
        val all = englishIds()
        val slice = all.drop((page - 1) * 25).take(25)
        return coroutineScope { slice.map { gid -> async { runCatching { info(gid.toString()) }.getOrNull() } }.awaitAll() }
            .filterNotNull().map { it.toSeries(detailed = false) }
    }

    private suspend fun englishIds(): IntArray {
        english?.takeIf { System.currentTimeMillis() - it.first < 60 * 60_000L }?.let { return it.second }
        return lock.withLock {
            english?.takeIf { System.currentTimeMillis() - it.first < 60 * 60_000L }?.let { return@withLock it.second }
            ints(bytes("$LTN/n/index-english.nozomi"), 0).also { english = System.currentTimeMillis() to it }
        }
    }

    private suspend fun version(): String {
        indexVersion?.takeIf { System.currentTimeMillis() - it.first < 10 * 60_000L }?.let { return it.second }
        return text("$LTN/galleriesindex/version?_=${System.currentTimeMillis()}").trim().also { indexVersion = System.currentTimeMillis() to it }
    }

    private class Node(val keys: List<ByteArray>, val datas: List<Pair<Long, Int>>, val subnodes: LongArray)

    private suspend fun node(indexUrl: String, address: Long): Node {
        val key = "$indexUrl@$address"
        synchronized(nodes) { nodes[key] }?.let { return it }
        return decodeNode(bytes(indexUrl, address until address + MAX_NODE_SIZE)).also { synchronized(nodes) { nodes[key] = it } }
    }

    /**
     * Gallery ids for one search word; empty when the word is unknown, null when it is so common ("the", "of") that
     * its list (megabytes) would barely narrow the result and is skipped.
     */
    private suspend fun galleryIdsFor(word: String): List<Int>? {
        val v = version()
        val indexUrl = "$LTN/galleriesindex/galleries.$v.index"
        val key = MessageDigest.getInstance("SHA-256").digest(word.toByteArray()).copyOf(4)
        var node = node(indexUrl, 0)
        var depth = 0
        while (depth++ < 12) {
            if (node.keys.isEmpty()) return emptyList()
            var where = node.keys.size
            var found = false
            for (i in node.keys.indices) {
                val cmp = compare(key, node.keys[i])
                if (cmp <= 0) {
                    where = i
                    found = cmp == 0
                    break
                }
            }
            if (found) {
                val (offset, length) = node.datas[where]
                if (length <= 0) return emptyList()
                if (length > COMMON_WORD_BYTES) return null
                val data = bytes("$LTN/galleriesindex/galleries.$v.data", offset until offset + length)
                val count = ByteBuffer.wrap(data).int
                if (count <= 0 || data.size != count * 4 + 4) return emptyList()
                return ints(data, 4).toList()
            }
            if (node.subnodes.all { it == 0L } || node.subnodes[where] == 0L) return emptyList()
            node = node(indexUrl, node.subnodes[where])
        }
        return emptyList()
    }

    // ------------------------------------------------------------------ details

    private suspend fun info(galleryId: String): Info {
        val js = text("$LTN/galleries/$galleryId.js")
        return ctx.json.decodeFromString(Info.serializer(), js.substring(js.indexOf('{')))
    }

    private suspend fun Info.toSeries(detailed: Boolean): SourceSeries {
        val tagNames = tags.orEmpty().map { t -> if (t.female == "1") "female:${t.tag}" else if (t.male == "1") "male:${t.tag}" else t.tag }
        return SourceSeries(
            sourceId = this@HitomiAdapter.id,
            id = id,
            title = Galleries.cleanTitle(title),
            altTitles = Galleries.titles(title, japanese_title).drop(1),
            coverUrl = files.firstOrNull()?.let { runCatching { thumbnail(it.hash) }.getOrNull() },
            authors = artists.orEmpty().map { it.artist } + groups.orEmpty().map { it.group },
            genres = listOfNotNull(type) + tagNames,
            kind = ContentKind.OTHER,
            status = PublicationStatus.COMPLETED,
            description = listOfNotNull(
                parodys?.map { it.parody }?.takeIf { it.isNotEmpty() }?.let { "Parody: ${it.joinToString()}" },
                "${files.size} pages",
            ).joinToString("\n"),
            webUrl = seriesWebUrl(id),
            detailed = detailed,
        )
    }

    override suspend fun details(series: SourceSeries): SourceSeries =
        if (series.detailed) series else info(series.id).toSeries(detailed = true)

    override suspend fun chapters(series: SourceSeries, languages: List<String>): List<SourceChapter> {
        val info = info(series.id)
        val uploaded = info.date?.let { d -> runCatching { OffsetDateTime.parse(d.replace(' ', 'T') + ":00", DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli() }.getOrNull() }
        return listOf(Galleries.chapter(id, series.id, uploaded, info.files.size, seriesWebUrl(series.id)))
    }

    override suspend fun pages(chapter: SourceChapter, dataSaver: Boolean): List<ReaderPage> {
        val info = info(chapter.id)
        val g = gg()
        return info.files.mapIndexed { i, f ->
            val url = imageUrl(f.hash, g)
            ReaderPage(i, url, imageHeaders(url))
        }
    }

    // ------------------------------------------------------------------ image URLs (common.js)

    private suspend fun gg(): Gg {
        gg?.takeIf { System.currentTimeMillis() - it.at < 30 * 60_000L }?.let { return it }
        val js = text("$LTN/gg.js")
        val cases = CASE.findAll(js).map { it.groupValues[1].toInt() }.toSet()
        val default = DEFAULT_O.find(js)?.groupValues?.get(1)?.toInt() ?: 0
        val inCase = CASE_O.find(js)?.groupValues?.get(1)?.toInt() ?: 1
        val b = B.find(js)?.groupValues?.get(1) ?: error("Hitomi: gg.js format changed")
        return Gg(cases, inCase, default, b, System.currentTimeMillis()).also { gg = it }
    }

    /** `parseInt(last char + the two before it, 16)`: the image's directory number. */
    private fun s(hash: String): Int = (hash.takeLast(1) + hash.dropLast(1).takeLast(2)).toInt(16)

    internal fun imageUrl(hash: String, m: (Int) -> Int, b: String): String {
        val g = s(hash)
        return "https://w${1 + m(g)}.$DOMAIN/$b$g/$hash.webp"
    }

    private fun imageUrl(hash: String, g: Gg) = imageUrl(hash, g::m, g.b)

    private suspend fun thumbnail(hash: String): String {
        val g = gg()
        return "https://${'a' + g.m(s(hash))}tn.$DOMAIN/webpbigtn/${hash.takeLast(1)}/${hash.dropLast(1).takeLast(2)}/$hash.webp"
    }

    override fun imageHeaders(imageUrl: String) = mapOf("Referer" to "$baseUrl/", "User-Agent" to ctx.browserUserAgent)

    override fun seriesWebUrl(seriesId: String) = "$baseUrl/galleries/$seriesId.html"

    companion object {
        const val ID = "hitomi"
        private const val DOMAIN = "gold-usergeneratedcontent.net"
        private const val LTN = "https://ltn.$DOMAIN"
        private const val MAX_NODE_SIZE = 464L
        private const val MAX_RESULTS = 12
        /** About 75,000 galleries: words this common are skipped (all-common queries search nothing). */
        private const val COMMON_WORD_BYTES = 300_000
        private val CASE = Regex("""case (\d+):""")
        private val DEFAULT_O = Regex("""var o = (\d)""")
        private val CASE_O = Regex("""o = (\d); break;""")
        private val B = Regex("""b: '([^']+)'""")

        private fun ints(data: ByteArray, from: Int): IntArray {
            val buf = ByteBuffer.wrap(data, from, data.size - from)
            return IntArray((data.size - from) / 4) { buf.int }
        }

        /** Unsigned lexicographic comparison over the shorter length, as in search.js. */
        private fun compare(a: ByteArray, b: ByteArray): Int {
            for (i in 0 until minOf(a.size, b.size)) {
                val x = a[i].toInt() and 0xff
                val y = b[i].toInt() and 0xff
                if (x != y) return if (x < y) -1 else 1
            }
            return 0
        }

        private fun decodeNode(data: ByteArray): Node {
            val buf = ByteBuffer.wrap(data)
            val keys = List(buf.int) {
                val size = buf.int
                require(size in 1..32) { "Hitomi: bad index node" }
                ByteArray(size).also { buf.get(it) }
            }
            val datas = List(buf.int) { buf.long to buf.int }
            val subnodes = LongArray(17) { buf.long }
            return Node(keys, datas, subnodes)
        }
    }
}
