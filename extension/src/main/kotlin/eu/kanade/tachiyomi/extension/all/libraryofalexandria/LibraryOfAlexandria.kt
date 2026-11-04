package eu.kanade.tachiyomi.extension.all.libraryofalexandria

import alexandria.core.Aggregator
import alexandria.core.ChapterUrl
import alexandria.core.SeriesCard
import alexandria.core.SeriesKey
import alexandria.core.SeriesState
import alexandria.core.cache.CacheManager
import alexandria.core.config.AggregatorSettings
import alexandria.core.fallback.FallbackInterceptor
import alexandria.core.metadata.AniListClient
import alexandria.core.metadata.MangaBakaClient
import alexandria.core.metadata.MetadataResolver
import alexandria.core.net.ImageHeaderInterceptor
import alexandria.core.model.PublicationStatus
import alexandria.core.ranking.ProviderHealth
import alexandria.core.reconciliation.Release
import alexandria.core.sources.AdapterContext
import alexandria.core.sources.SourceRegistry
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Library of Alexandria: one catalogue source backed by several bundled content providers plus AniList and
 * MangaBaka metadata. All aggregation logic lives in the host-independent `:core` module; this class only
 * adapts it to the extensions-lib 1.6 API (suspend `getMangaUpdate` / `getPageList`, `memo` fields).
 */
class LibraryOfAlexandria : HttpSource(), ConfigurableSource {

    override val name = "Library of Alexandria"
    /** English only. Published as "all" until 1.6.3; [id] keeps that original value so libraries stay attached. */
    override val lang = "en"
    override val id: Long = BuildConfig.SOURCE_ID
    override val baseUrl = "https://mangabaka.org"
    override val supportsLatest = true

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", Context.MODE_PRIVATE)
    }

    /** Cache lives in its own preference file so it never bloats the user's settings (or their backups). */
    private val store by lazy {
        runCatching { SharedPrefsStore(Injekt.get<Application>().getSharedPreferences("source_${id}_cache", Context.MODE_PRIVATE)) }
            .getOrElse { alexandria.core.cache.InMemoryStore() }
    }

    private fun settings(): AggregatorSettings = Settings.read(preferences, aggregator.registry.all.map { it.id })

    private val userAgent get() = "LibraryOfAlexandria/${BuildConfig.VERSION} (Tachiyomi extension; +https://github.com/KarsTheUltimateCreation/library-of-alexandria)"

    /**
     * Default headers, which hosts also use for browse and library covers. They have to suit every provider's
     * cover CDN at once: MangaDex's answers browser user agents with an HTML page, so a client user agent is
     * sent; MangaPill's refuses requests without its Referer, which no other CDN minds. Covers needing other
     * headers (Hiperdex) get them from [ImageHeaderInterceptor] when the host loads covers through [client].
     */
    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", userAgent)
        .set("Referer", DEFAULT_REFERER)

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .addInterceptor(FallbackInterceptor({ aggregatorOrNull?.reader }, { settings() }))
            .addInterceptor(ImageHeaderInterceptor({ runCatching { aggregator.registry }.getOrNull() }, DEFAULT_REFERER))
            .build()
    }

    @Volatile private var aggregatorOrNull: Aggregator? = null

    private val aggregator: Aggregator by lazy {
        val cache = CacheManager(store, maxPersistedEntries = 250)
        val health = ProviderHealth(store)
        val ctx = AdapterContext(client, json, userAgent)
        val registry = SourceRegistry.defaults(ctx, health)
        val meta = MetadataResolver(MangaBakaClient(client, json, userAgent), AniListClient(client, json, userAgent), cache, log = ::log)
        Aggregator(registry, meta, cache, client, log = ::log).also { aggregatorOrNull = it }
    }

    // ------------------------------------------------------------------ browse

    override suspend fun getPopularManga(page: Int): MangasPage {
        val (cards, more) = aggregator.popular(page, settings())
        return MangasPage(cards.map { it.toSManga() }, more)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val (cards, more) = aggregator.latest(page, settings())
        return MangasPage(cards.map { it.toSManga() }, more)
    }

    override fun getFilterList(): FilterList = Filters.list(runCatching { settings().adultContent }.getOrDefault(false))

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val catalogFilters = runCatching { Filters.parse(filters) }.getOrNull()
        if (catalogFilters != null && !catalogFilters.isEmpty) {
            val (cards, more) = aggregator.browse(query.trim(), catalogFilters, page, settings())
            return MangasPage(cards.map { it.toSManga() }, more)
        }
        if (page > 1) return MangasPage(emptyList(), false)
        // Direct identity lookup: "mb:3397", "al:105398", "p:weebcentral:<id>" (also used for manual linking).
        SeriesKey.parse(query.trim())?.let { key ->
            val update = aggregator.update(key, null, fetchDetails = true, fetchChapters = false, settings())
            return MangasPage(listOf(SManga.create().apply { url = key.value; title = update.details.title; thumbnail_url = update.details.coverUrl }), false)
        }
        if (query.isBlank()) return getPopularManga(1)
        return MangasPage(aggregator.search(query.trim(), settings()).map { it.toSManga() }, false)
    }

    private fun SeriesCard.toSManga() = SManga.create().apply {
        url = key.value
        title = this@toSManga.title
        thumbnail_url = coverUrl
    }

    // ------------------------------------------------------------------ details + chapters

    override suspend fun getMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean): SMangaUpdate {
        val key = SeriesKey.parse(manga.url) ?: throw IllegalArgumentException("Unrecognised Library of Alexandria entry: ${manga.url}")
        val known = readMemo(manga)
        val s = settings()
        val update = aggregator.update(key, known, fetchDetails, fetchChapters, s)

        val newManga = if (fetchDetails) {
            SManga.create().apply {
                url = manga.url
                title = update.details.title
                author = update.details.author
                artist = update.details.artist
                description = update.details.description
                genre = update.details.genres.joinToString()
                status = update.details.status.toSManga()
                thumbnail_url = update.details.coverUrl ?: manga.thumbnail_url
                initialized = true
            }
        } else {
            manga
        }
        writeMemo(newManga, update.state)

        val newChapters = update.chapters?.map { c ->
            SChapter.create().apply {
                url = c.url
                name = c.name
                chapter_number = c.number
                date_upload = c.uploadedAt
                scanlator = c.scanlator
                runCatching {
                    memo = buildJsonObject { put(MEMO_RELEASES, json.encodeToJsonElement(ListSerializer(Release.serializer()), c.releases)) }
                }
            }
        } ?: chapters
        return SMangaUpdate(newManga, newChapters)
    }

    // ------------------------------------------------------------------ pages

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val releases = runCatching {
            chapter.memo[MEMO_RELEASES]?.let { json.decodeFromJsonElement(ListSerializer(Release.serializer()), it) }
        }.getOrNull()
        val resolution = aggregator.pages(chapter.url, releases, settings())
        return resolution.pages.map { Page(it.index, "", it.imageUrl) }
    }

    override fun imageRequest(page: Page): Request {
        val url = page.imageUrl ?: throw IllegalStateException("Page ${page.index} has no image URL")
        val token = FallbackInterceptor.pageToken(url)
        val adapterHeaders = token?.sourceId?.let { aggregator.registry[it] }?.imageHeaders(FallbackInterceptor.stripTag(url)).orEmpty()
        val h = Headers.Builder().addAll(headers)
        adapterHeaders.forEach { (k, v) -> h.set(k, v) }
        return Request.Builder().url(url).headers(h.build()).build()
    }

    // ------------------------------------------------------------------ web links

    override fun getMangaUrl(manga: SManga): String = when (val key = SeriesKey.parse(manga.url)) {
        is SeriesKey.MangaBaka -> "https://mangabaka.org/${key.id}"
        is SeriesKey.AniList -> "https://anilist.co/manga/${key.id}"
        is SeriesKey.Provider -> aggregator.registry[key.sourceId]?.seriesWebUrl(key.seriesId) ?: baseUrl
        null -> baseUrl
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val first = runCatching {
            chapter.memo[MEMO_RELEASES]?.let { json.decodeFromJsonElement(ListSerializer(Release.serializer()), it) }?.firstOrNull()
        }.getOrNull()
        if (first != null) {
            first.webUrl?.let { return it }
            aggregator.registry[first.sourceId]?.let { return it.seriesWebUrl(first.seriesId) }
        }
        val key = ChapterUrl.parse(chapter.url)?.first ?: return baseUrl
        return getMangaUrl(SManga.create().apply { url = key.value })
    }

    // ------------------------------------------------------------------ settings

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val registry = aggregator.registry
        Settings.build(screen, preferences, registry.all.map { it.id to it.name }, registry.all.filter { it.adult }.map { it.id }.toSet()) { action ->
            when (action) {
                Settings.Action.CLEAR_METADATA -> { aggregator.cache.clear("meta:"); aggregator.cache.clear("cover:") }
                Settings.Action.CLEAR_LINKS -> { aggregator.cache.clear("state:"); aggregator.cache.clear("choice:") }
                Settings.Action.CLEAR_ALL -> { aggregator.cache.clear(); aggregator.registry.health.reset() }
            }
        }
    }

    // ------------------------------------------------------------------ memo helpers

    /** Provider links are kept in the manga memo so they survive cache clears and app reinstalls (Mihon persists memos). */
    private fun readMemo(manga: SManga): SeriesState? = runCatching {
        manga.memo[MEMO_STATE]?.let { json.decodeFromJsonElement(SeriesState.serializer(), it) }
    }.getOrNull()

    private fun writeMemo(manga: SManga, state: SeriesState) {
        runCatching {
            // Only links travel in the memo; metadata is re-fetchable and would bloat the host database.
            val slim = state.copy(metadata = null, bindings = state.bindings.mapValues { (_, b) -> b.copy(series = b.series.copy(description = null, altTitles = b.series.altTitles.take(5), genres = emptyList())) })
            manga.memo = JsonObject(manga.memo + (MEMO_STATE to json.encodeToJsonElement(SeriesState.serializer(), slim)))
        }
    }

    private fun PublicationStatus.toSManga() = when (this) {
        PublicationStatus.ONGOING -> SManga.ONGOING
        PublicationStatus.COMPLETED -> SManga.COMPLETED
        PublicationStatus.HIATUS -> SManga.ON_HIATUS
        PublicationStatus.CANCELLED -> SManga.CANCELLED
        PublicationStatus.UNKNOWN -> SManga.UNKNOWN
    }

    private fun log(message: String) {
        println("[LibraryOfAlexandria] $message")
    }

    companion object {
        private const val MEMO_STATE = "loa"
        private const val MEMO_RELEASES = "r"
        private const val DEFAULT_REFERER = "https://mangapill.com/"
    }
}
