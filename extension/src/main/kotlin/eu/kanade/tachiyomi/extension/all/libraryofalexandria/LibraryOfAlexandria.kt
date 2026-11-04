package eu.kanade.tachiyomi.extension.all.libraryofalexandria

import alexandria.core.Aggregator
import alexandria.core.ChapterUrl
import alexandria.core.Refresh
import alexandria.core.SeriesCard
import alexandria.core.SeriesKey
import alexandria.core.SeriesState
import alexandria.core.cache.CacheManager
import alexandria.core.cache.ReleaseMemo
import alexandria.core.config.AggregatorSettings
import alexandria.core.fallback.FallbackInterceptor
import alexandria.core.metadata.AniListClient
import alexandria.core.metadata.MangaBakaClient
import alexandria.core.metadata.MetadataResolver
import alexandria.core.net.ImageHeaderInterceptor
import alexandria.core.net.ServiceGate
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    private val store by lazy { prefsStore("source_${id}_cache") }

    /**
     * Last known provider chapter lists, in a third file: they are the largest entries, and keeping them apart means
     * the small, frequently read cache above is never rewritten together with them. Bounded (entries and size).
     */
    private val listStore by lazy { prefsStore("source_${id}_chapters") }

    private fun prefsStore(name: String): alexandria.core.cache.KeyValueStore =
        runCatching { SharedPrefsStore(Injekt.get<Application>().getSharedPreferences(name, Context.MODE_PRIVATE)) }
            .getOrElse { alexandria.core.cache.InMemoryStore() }

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
        // Confirmed provider links get their own budget so cover and metadata churn never evicts them.
        val cache = CacheManager(store, maxPersistedEntries = 250, groupLimits = mapOf("state:" to 400))
        val lists = CacheManager(listStore, maxPersistedEntries = MAX_STORED_LISTS, maxPersistedChars = MAX_STORED_LIST_CHARS)
        val health = ProviderHealth(store)
        val ctx = AdapterContext(client, json, userAgent)
        val registry = SourceRegistry.defaults(ctx, health)
        val meta = MetadataResolver(MangaBakaClient(client, json, userAgent), AniListClient(client, json, userAgent), cache, log = ::log)
        Aggregator(registry, meta, cache, client, log = ::log, listCache = lists).also { aggregatorOrNull = it }
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

    override fun getFilterList(): FilterList = Filters.list()

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
        // The host asked (library update, refresh, first open): fetch again, except lists fetched moments ago.
        val update = aggregator.update(key, known, fetchDetails, fetchChapters, s, Refresh.EXPLICIT)

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

        val list = update.chapters
        // Hosts write the whole chapter list in one SQL statement (SQLite caps it at 1 MB): the release memo is kept
        // compact and shrinks with the chapter count. Chapters without one are resolved from the provider links.
        val perChapter = list?.let { releasesPerChapter(it.size) } ?: 0
        val fresh = list?.map { c ->
            SChapter.create().apply {
                url = c.url
                name = c.name
                chapter_number = c.number
                date_upload = c.uploadedAt
                scanlator = c.scanlator
                if (perChapter > 0) runCatching { memo = buildJsonObject { put(MEMO_RELEASES, encodeReleases(c.releases, perChapter)) } }
            }
        }
        val newChapters = when {
            fresh == null -> chapters
            // A linked source is down and nothing of it was cached: its chapters are missing from this list only
            // because of the outage. Keep the ones the host already has instead of letting it delete them.
            update.unavailableSources.isNotEmpty() -> {
                val urls = fresh.map { it.url }.toSet()
                val kept = chapters.filter { it.url !in urls }
                if (kept.isNotEmpty()) log("${key.value}: keeping ${kept.size} chapter(s) while ${update.unavailableSources.joinToString()} is unavailable")
                (fresh + kept).sortedByDescending { it.chapter_number }
            }
            else -> fresh
        }
        return SMangaUpdate(newManga, newChapters)
    }

    // ------------------------------------------------------------------ pages

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val resolution = aggregator.pages(chapter.url, decodeReleases(chapter), settings())
        // Lazy pages (E-Hentai viewer pages) are resolved one at a time by the host through getImageUrl.
        return resolution.pages.map { if (it.lazy) Page(it.index, it.imageUrl, null) else Page(it.index, "", it.imageUrl) }
    }

    override suspend fun getImageUrl(page: Page): String = aggregator.resolveImage(page.url, settings())

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
        val first = decodeReleases(chapter)?.firstOrNull()
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
        Settings.build(screen, preferences, registry.all.map { it.id to it.name }) { action ->
            when (action) {
                Settings.Action.CLEAR_METADATA -> { aggregator.cache.clear("meta:"); aggregator.cache.clear("cover:") }
                Settings.Action.CLEAR_LINKS -> {
                    aggregator.cache.clear("state:"); aggregator.cache.clear("choice:"); aggregator.cache.clear("seriespref:")
                    aggregator.clearChapterLists()
                }
                Settings.Action.CLEAR_ALL -> {
                    aggregator.cache.clear(); aggregator.clearChapterLists()
                    aggregator.registry.health.reset(); ServiceGate.reset()
                }
            }
        }
    }

    // ------------------------------------------------------------------ memo helpers

    /** Releases kept per chapter so the list's memos stay under [MEMO_BUDGET] characters (0 = none). */
    private fun releasesPerChapter(chapters: Int): Int =
        if (chapters == 0) 0 else (MEMO_BUDGET / (chapters * RELEASE_CHARS)).coerceIn(0, MAX_MEMO_RELEASES)

    /**
     * "sourceId<US>seriesId<US>chapterId<US>confidence%" per release ([ReleaseMemo]): what the reader needs to fetch
     * pages after a restart, before the full release list is known again. It is a bootstrap, not the authority:
     * page resolution merges it with the complete list whenever that is available.
     */
    private fun encodeReleases(releases: List<Release>, max: Int) =
        JsonArray(releases.take(max).map { JsonPrimitive(ReleaseMemo.encode(it)) })

    /** Reads compact memos (1.6.5's without confidence, too) and, for chapters stored before 1.6.5, full release objects. */
    private fun decodeReleases(chapter: SChapter): List<Release>? = runCatching {
        (chapter.memo[MEMO_RELEASES] as? JsonArray)?.mapNotNull { el ->
            when (el) {
                is JsonPrimitive -> ReleaseMemo.decode(el.content)
                is JsonObject -> json.decodeFromJsonElement(Release.serializer(), el)
                else -> null
            }
        }?.takeIf { it.isNotEmpty() }
    }.getOrNull()

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
        private const val MEMO_BUDGET = 200_000
        private const val RELEASE_CHARS = 84
        private const val MAX_MEMO_RELEASES = 3

        /** Provider chapter lists kept across restarts, and the largest one stored (≈1,500 chapters). */
        private const val MAX_STORED_LISTS = 48
        private const val MAX_STORED_LIST_CHARS = 150_000
    }
}
