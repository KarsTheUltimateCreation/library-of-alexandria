package alexandria.core.model

import kotlinx.serialization.Serializable

/** Broad publication format. Used to reject e.g. a light novel when looking for the comic. */
enum class ContentKind { MANGA, MANHWA, MANHUA, COMIC, NOVEL, OTHER, UNKNOWN;

    val isComic: Boolean get() = this == MANGA || this == MANHWA || this == MANHUA || this == COMIC

    companion object {
        fun parse(raw: String?): ContentKind {
            val s = raw?.trim()?.lowercase() ?: return UNKNOWN
            return when {
                s.isEmpty() -> UNKNOWN
                "novel" in s -> NOVEL
                s == "ln" -> NOVEL
                "manhwa" in s || "webtoon" in s -> MANHWA
                "manhua" in s -> MANHUA
                "manga" in s || "one_shot" in s || "one-shot" in s || "oneshot" in s -> MANGA
                "oel" in s || "comic" in s -> COMIC
                "doujin" in s -> OTHER
                else -> UNKNOWN
            }
        }
    }
}

enum class PublicationStatus { ONGOING, COMPLETED, HIATUS, CANCELLED, UNKNOWN;

    companion object {
        fun parse(raw: String?): PublicationStatus {
            val s = raw?.trim()?.lowercase() ?: return UNKNOWN
            return when {
                s.startsWith("ongoing") || s == "releasing" || s == "publishing" -> ONGOING
                s.startsWith("complete") || s == "finished" -> COMPLETED
                s == "hiatus" || s == "on hiatus" -> HIATUS
                s.startsWith("cancel") || s == "discontinued" -> CANCELLED
                else -> UNKNOWN
            }
        }
    }
}

/** Identifiers in shared external databases. Equal identifiers are the strongest identity signal. */
@Serializable
data class ExternalIds(
    val mangabaka: Long? = null,
    val anilist: Long? = null,
    val mal: Long? = null,
    val mangaUpdates: String? = null,
    val mangadex: String? = null,
) {
    fun merge(other: ExternalIds) = ExternalIds(
        mangabaka = mangabaka ?: other.mangabaka,
        anilist = anilist ?: other.anilist,
        mal = mal ?: other.mal,
        mangaUpdates = mangaUpdates ?: other.mangaUpdates,
        mangadex = mangadex ?: other.mangadex,
    )

    val isEmpty: Boolean get() = mangabaka == null && anilist == null && mal == null && mangaUpdates == null && mangadex == null

    /** +1 when some shared identifier agrees, -1 when one disagrees and none agree, 0 when nothing comparable. */
    fun compare(other: ExternalIds): Int {
        var agree = 0
        var disagree = 0
        fun <T> check(a: T?, b: T?) {
            if (a == null || b == null) return
            if (a == b) agree++ else disagree++
        }
        check(mangabaka, other.mangabaka)
        check(anilist, other.anilist)
        check(mal, other.mal)
        check(mangaUpdates?.lowercase(), other.mangaUpdates?.lowercase())
        check(mangadex, other.mangadex)
        return when {
            agree > 0 && disagree == 0 -> 1
            agree > 0 -> 0 // conflicting databases: treat as inconclusive
            disagree > 0 -> -1
            else -> 0
        }
    }
}

enum class CoverOrigin(val trust: Double) {
    /** Full-resolution artwork from a metadata database (MangaBaka raw). */
    METADATA_ORIGINAL(1.0),

    /** Original upload on a content provider that stores source files (MangaDex). */
    PROVIDER_ORIGINAL(0.95),

    /** Large, but resized, metadata artwork (AniList extraLarge). */
    METADATA_LARGE(0.8),

    /** A content provider's thumbnail. */
    PROVIDER_THUMBNAIL(0.5),
}

@Serializable
data class CoverCandidate(
    val url: String,
    val provider: String,
    val origin: String,
    val width: Int? = null,
    val height: Int? = null,
    val bytes: Long? = null,
    /** Language of the cover edition when known (e.g. a translated volume cover). */
    val language: String? = null,
    val headers: Map<String, String> = emptyMap(),
) {
    val coverOrigin: CoverOrigin get() = runCatching { CoverOrigin.valueOf(origin) }.getOrDefault(CoverOrigin.PROVIDER_THUMBNAIL)
}

/** Consolidated series metadata from AniList / MangaBaka / a provider. */
@Serializable
data class SeriesMetadata(
    val provider: String,
    val ids: ExternalIds = ExternalIds(),
    val title: String,
    val englishTitle: String? = null,
    val romanizedTitle: String? = null,
    val nativeTitle: String? = null,
    val altTitles: List<String> = emptyList(),
    val kind: ContentKind = ContentKind.UNKNOWN,
    val status: PublicationStatus = PublicationStatus.UNKNOWN,
    val originalLanguage: String? = null,
    val year: Int? = null,
    /** Original-publication chapter count, kept lossless (MangaBaka returns strings such as "201"). */
    val totalChapters: String? = null,
    val description: String? = null,
    val authors: List<String> = emptyList(),
    val artists: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val covers: List<CoverCandidate> = emptyList(),
    /** Small pre-resized image for browse grids (never used as the final cover). */
    val thumbnailUrl: String? = null,
    val webUrl: String? = null,
) {
    /** Every title variant, most authoritative first, without duplicates. */
    fun allTitles(): List<String> =
        (listOfNotNull(title, englishTitle, romanizedTitle, nativeTitle) + altTitles)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }

    /** A small set of titles worth sending as search queries to content providers. */
    fun searchQueries(max: Int = 3): List<String> =
        listOfNotNull(englishTitle, title, romanizedTitle)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .take(max)
}

/** A series as listed by one content provider. */
@Serializable
data class SourceSeries(
    val sourceId: String,
    /** Provider-specific stable id or path. Opaque to the core. */
    val id: String,
    val title: String,
    val altTitles: List<String> = emptyList(),
    val coverUrl: String? = null,
    val ids: ExternalIds = ExternalIds(),
    val kind: ContentKind = ContentKind.UNKNOWN,
    val status: PublicationStatus = PublicationStatus.UNKNOWN,
    val year: Int? = null,
    val originalLanguage: String? = null,
    val description: String? = null,
    val authors: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val webUrl: String? = null,
    /** True once details were fetched (alt titles, ids); search hits are often partial. */
    val detailed: Boolean = false,
) {
    fun allTitles(): List<String> = (listOf(title) + altTitles).filter { it.isNotBlank() }.distinctBy { it.lowercase() }

    fun asMetadata(): SeriesMetadata = SeriesMetadata(
        provider = sourceId,
        ids = ids,
        title = title,
        altTitles = altTitles,
        kind = kind,
        status = status,
        originalLanguage = originalLanguage,
        year = year,
        description = description,
        authors = authors,
        genres = genres,
        covers = listOfNotNull(coverUrl?.let { CoverCandidate(it, sourceId, CoverOrigin.PROVIDER_THUMBNAIL.name) }),
        webUrl = webUrl,
    )
}

/** A chapter release as listed by one content provider. */
@Serializable
data class SourceChapter(
    val sourceId: String,
    val seriesId: String,
    /** Provider-specific stable chapter id or path. */
    val id: String,
    /** The label exactly as the provider shows it ("Ch. 010 - Awakening"). */
    val label: String,
    /** Structured chapter number when the provider exposes one (MangaDex). Kept as a string: "10.10" != "10.1". */
    val number: String? = null,
    val volume: String? = null,
    val title: String? = null,
    /** BCP-47-ish language code ("en", "pt-br"). */
    val language: String = "en",
    val group: String? = null,
    val official: Boolean? = null,
    val uploadedAt: Long? = null,
    val pageCount: Int? = null,
    /** Chapter only readable on an external site; it cannot provide pages. */
    val externalOnly: Boolean = false,
    val webUrl: String? = null,
)

@Serializable
data class ReaderPage(
    val index: Int,
    val imageUrl: String,
    val headers: Map<String, String> = emptyMap(),
)
