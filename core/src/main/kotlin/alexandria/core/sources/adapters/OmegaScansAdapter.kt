package alexandria.core.sources.adapters

import alexandria.core.model.ContentKind
import alexandria.core.model.PublicationStatus
import alexandria.core.model.ReaderPage
import alexandria.core.model.SourceChapter
import alexandria.core.model.SourceSeries
import alexandria.core.net.RateLimiter
import alexandria.core.net.fetchText
import alexandria.core.sources.AdapterContext
import alexandria.core.sources.SourceAdapter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.Jsoup
import java.time.OffsetDateTime

/**
 * Omega Scans (English, adult). HeanCMS JSON API at api.omegascans.org; endpoints follow
 * keiyoushi/extensions-source `lib-multisrc/heancms` + `src/en/omegascans` (Apache-2.0).
 *
 * Series ids are "numericId/slug" (chapters are listed by numeric id, pages by slug). Paid (coin-locked)
 * chapters are left out: they cannot be read without an account.
 */
class OmegaScansAdapter(
    private val ctx: AdapterContext,
    override val baseUrl: String = "https://omegascans.org",
    private val apiUrl: String = "https://api.omegascans.org",
) : SourceAdapter {
    override val id = ID
    override val name = "Omega Scans"
    override val languages: Set<String> = setOf("en")
    override val adult = true
    override val imageHosts = setOf("media.omegascans.org")
    private val limiter = RateLimiter.shared("omegascans", 1, 1_000)

    @Serializable
    private class Query(val data: List<Series> = emptyList())

    @Serializable
    private class Series(
        val id: Int,
        @SerialName("series_slug") val slug: String,
        val title: String,
        val thumbnail: String? = null,
        val description: String? = null,
        val author: String? = null,
        val status: String? = null,
        @SerialName("alternative_names") val alternativeNames: String? = null,
        @SerialName("release_year") val releaseYear: String? = null,
        val tags: List<Tag>? = null,
        val badge: String? = null,
    )

    @Serializable
    private class Tag(val name: String)

    @Serializable
    private class ChapterPage(val data: List<Chapter> = emptyList(), val meta: Meta? = null)

    @Serializable
    private class Meta(@SerialName("current_page") val currentPage: Int = 1, @SerialName("last_page") val lastPage: Int = 1)

    @Serializable
    private class Chapter(
        val id: Int,
        @SerialName("chapter_name") val name: String,
        @SerialName("chapter_title") val title: String? = null,
        @SerialName("chapter_slug") val slug: String,
        @SerialName("created_at") val createdAt: String? = null,
        val price: Int? = null,
    )

    @Serializable
    private class PagePayload(val chapter: PageChapter, val paywall: Boolean = false)

    @Serializable
    private class PageChapter(@SerialName("chapter_data") val chapterData: PageData? = null)

    @Serializable
    private class PageData(val images: List<String>? = null)

    private suspend inline fun <reified T> get(url: HttpUrl): T {
        val request = Request.Builder().url(url)
            .header("User-Agent", ctx.browserUserAgent)
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", "$baseUrl/")
            .header("Origin", baseUrl)
            .build()
        return ctx.json.decodeFromString(ctx.client.fetchText(request, limiter = limiter))
    }

    private fun queryUrl(query: String, orderBy: String, page: Int) = "$apiUrl/query".toHttpUrl().newBuilder()
        .addQueryParameter("query_string", query)
        .addQueryParameter("status", "All")
        .addQueryParameter("order", "desc")
        .addQueryParameter("orderBy", orderBy)
        .addQueryParameter("series_type", "Comic")
        .addQueryParameter("page", page.toString())
        .addQueryParameter("perPage", "20")
        .addQueryParameter("tags_ids", "[]")
        .addQueryParameter("adult", "true")
        .build()

    override suspend fun search(query: String): List<SourceSeries> {
        if (query.isBlank()) return emptyList()
        return get<Query>(queryUrl(query.trim(), "total_views", 1)).data.map { it.toSeries() }
    }

    override suspend fun popular(page: Int) = get<Query>(queryUrl("", "total_views", page)).data.map { it.toSeries() }

    override suspend fun latest(page: Int) = get<Query>(queryUrl("", "latest", page)).data.map { it.toSeries() }

    override suspend fun details(series: SourceSeries): SourceSeries {
        if (series.detailed) return series
        val fresh = get<Series>("$apiUrl/series/${slugOf(series.id)}".toHttpUrl()).toSeries()
        return fresh.copy(altTitles = (series.altTitles + fresh.altTitles).distinct(), coverUrl = fresh.coverUrl ?: series.coverUrl, detailed = true)
    }

    override suspend fun chapters(series: SourceSeries, languages: List<String>): List<SourceChapter> {
        val numericId = series.id.substringBefore('/')
        val slug = slugOf(series.id)
        val all = mutableListOf<Chapter>()
        var page = 1
        do {
            val url = "$apiUrl/chapter/query".toHttpUrl().newBuilder()
                .addQueryParameter("page", page.toString())
                .addQueryParameter("perPage", "100")
                .addQueryParameter("series_id", numericId)
                .build()
            val result = get<ChapterPage>(url)
            all += result.data
            val more = result.meta?.let { it.currentPage < it.lastPage } ?: false
            page++
        } while (more && page <= 30)
        val now = System.currentTimeMillis()
        return all.filter { (it.price ?: 0) == 0 }.mapNotNull { c ->
            val uploaded = c.createdAt?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
            if (uploaded != null && uploaded > now) return@mapNotNull null // scheduled, not yet public
            SourceChapter(
                sourceId = id,
                seriesId = series.id,
                id = "$slug/${c.slug}",
                label = c.name.trim() + (c.title?.trim()?.takeIf { it.isNotEmpty() }?.let { ": $it" } ?: ""),
                language = "en",
                group = "Omega Scans",
                uploadedAt = uploaded,
                webUrl = "$baseUrl/series/$slug/${c.slug}",
            )
        }
    }

    override suspend fun pages(chapter: SourceChapter, dataSaver: Boolean): List<ReaderPage> {
        val payload = get<PagePayload>("$apiUrl/chapter/${chapter.id}".toHttpUrl())
        val images = payload.chapter.chapterData?.images.orEmpty()
        if (images.isEmpty() && payload.paywall) error("Omega Scans: paid chapter")
        return images.mapIndexed { i, src -> absolute(src).let { ReaderPage(i, it, imageHeaders(it)) } }
    }

    override fun imageHeaders(imageUrl: String): Map<String, String> = mapOf("Referer" to "$baseUrl/", "User-Agent" to ctx.browserUserAgent)

    override fun seriesWebUrl(seriesId: String) = "$baseUrl/series/${slugOf(seriesId)}"

    private fun slugOf(seriesId: String) = seriesId.substringAfter('/')

    private fun absolute(path: String) = if (path.startsWith("http")) path else "$apiUrl/${path.removePrefix("/")}"

    private fun Series.toSeries(): SourceSeries {
        val alts = alternativeNames?.split('|')?.map { it.trim() }?.filter { it.isNotEmpty() && !it.equals(title, true) }.orEmpty()
        val badge = badge?.trim()?.lowercase().orEmpty()
        return SourceSeries(
            sourceId = this@OmegaScansAdapter.id,
            id = "$id/$slug",
            title = title.trim(),
            altTitles = alts,
            coverUrl = thumbnail?.takeIf { it.isNotBlank() }?.let(::absolute),
            kind = if (badge.startsWith("douj")) ContentKind.OTHER else if (badge == "manga") ContentKind.MANGA else ContentKind.MANHWA,
            status = PublicationStatus.parse(status),
            year = releaseYear?.trim()?.toIntOrNull(),
            description = description?.let { Jsoup.parseBodyFragment(it).text() }?.takeUnless { it.isBlank() || it == "-" },
            authors = listOfNotNull(author?.trim()?.takeUnless { it.isEmpty() || it == "-" }),
            genres = tags.orEmpty().map { it.name },
            webUrl = "$baseUrl/series/$slug",
        )
    }

    companion object {
        const val ID = "omegascans"
    }
}
