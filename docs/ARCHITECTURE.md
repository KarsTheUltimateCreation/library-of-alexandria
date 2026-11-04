# Architecture and research findings

This document records what was verified about the extension ecosystem (November 2026) and why the
project is built the way it is. "Verified" means checked in source code, live API responses or a build
artifact; anything else is labelled.

## 1. Research findings

### Mihon (verified in source, `mihonapp/mihon` @ 8794e89, release v0.21.3)

| Topic | Finding |
|---|---|
| Supported extension libs | `ExtensionLoader.SUPPORTED_LIB_VERSIONS = listOf(1.4, 1.6)`; read via `metaData.getFloat("tachiyomix.extensionLib")`, falling back to the versionName prefix. |
| Package format | APK with `<uses-feature tachiyomi.extension>`, meta-data `tachiyomi.extension.class` / `.factory`, `tachiyomi.extension.nsfw`, `tachiyomix.name`, `tachiyomix.contentWarning`, `tachiyomix.extensionLib`. Must be signed; updates must keep the same signature. |
| Class loading | `DelegateLastClassLoader` per extension (boot → extension → app). Bundling a second kotlin-stdlib/OkHttp would shadow the app's copies, so the APK ships **only** our classes. |
| Lib 1.6 API | Suspend `getPopularManga/getLatestUpdates/getSearchManga/getMangaUpdate(manga, chapters, fetchDetails, fetchChapters)/getPageList`; `SManga.memo` and `SChapter.memo` (`JsonObject`) are persisted by the app (`remote_memo` columns). |
| Calling other extensions | **Not possible.** Mihon's Injekt bridge (`MetroInjektRegistrar`) exposes only `Application`, `Context`, `Json`, `ProtoBuf`, `XML`, `NetworkHelper`, `JavaScriptEngine`; it is read-only. `SourceManager` is not reachable. |
| Image loading | `HttpSource.getImage(page)` → `client.newCachelessCallWithProgress(imageRequest(page))`: images go through the **extension's own OkHttp client**, so an interceptor sees image failures. Covers of `HttpSource`s also use the source client. |
| Preferences | `ConfigurableSource.setupPreferenceScreen`; stubs provide `SwitchPreferenceCompat`, `ListPreference`, `MultiSelectListPreference`, `EditTextPreference`, `CheckBoxPreference`. **No `PreferenceCategory`.** |
| Repository format | `repo.json` (legacy, may carry `index_v2`) → `index.pb` (gzipped protobuf) or JSON; legacy `index.min.json` still parsed. Keiyoushi's live `index.min.json` now only holds "update your app" stubs; the real catalog is in `index.pb`. |

### Tachimanga (verified from the official site and changelog only — the runtime is closed source)

- Extension lib 1.6 support added in v4.16 (Jun 2026); v4.18 "uses `getMangaUpdate`".
- Repositories: "the repository URL usually ends with index.pb"; repos provide "third-party extension
  files (.jar)". Keiyoushi publishes a signed **JVM jar** next to every APK (`Resources.jarUrl`, proto
  field 501). Library of Alexandria does the same.
- Changelog shows WebView/Cloudflare shims, extension-settings persistence fixes, cookie support.
- **Not verifiable without the app**: whether `memo` is persisted, whether images use the extension
  client (needed for image-level fallback), which preference widgets render, `getSharedPreferences`
  with custom names. The code is written to degrade safely on each of these (see §3).

### Existing extension code

- **Keiyoushi extensions-source** (Apache-2.0, very active): each source now depends on Keiyoushi's
  `core` module + KSP code generation, so sources cannot be dropped in as libraries. Their parsing logic
  is easy to port.
- **Approach A (invoke installed extensions)**: impossible (see above).
- **Approach B (bundle whole extension APKs)**: would require shipping Keiyoushi's core + KSP output and
  emulating `HttpSource` for each; brittle, and 2+ MB per source.
- **Kotatsu parsers / Nyora (Approach D)**: not adopted for v1, but feasible on Mihon.
  `KotatsuApp/kotatsu-parsers` is archived; the active fork is `YakaTeam/kotatsu-parsers` (**GPL-3.0**).
  `Nyora-Manga/nyora-mihon` proves the engine can be bundled into one Mihon extension (~900 sources), but
  it declares **no license** (so its code cannot be reused), documents Mihon only, and relies on Mihon's
  WebView for Cloudflare. Adopting the Kotatsu engine would require (a) relicensing Library of Alexandria
  as GPL-3.0 (Apache-2.0 code adapted from Keiyoushi is GPLv3-compatible), (b) implementing Kotatsu's
  `MangaLoaderContext` (cookies, JS evaluation, WebView) on the extension API, and (c) proving it runs in
  Tachimanga's JVM runtime. Since Tachimanga compatibility takes priority over source count, the portable
  adapter approach was chosen; the `SourceAdapter` interface is the integration point if that changes.
- **Chosen: Approach C + E** — a small portable adapter interface (`SourceAdapter`) in a pure-JVM core,
  with provider logic adapted from maintained Keiyoushi sources. Only libraries every host already
  provides are used (OkHttp, Jsoup, kotlinx-serialization, coroutines).

### Metadata services (verified with live requests)

- **MangaBaka** `https://api.mangabaka.org`, no auth. Limits: search 30/min, others 180/min (429 +
  `Retry-After`). Series carry native/romanized/alt titles, `total_chapters` (string), status, type, and
  `source.{anilist,my_anime_list,manga_updates}.id`, plus a raw cover with **width, height and byte size**.
  `GET /v1/source/anilist/{id}` maps AniList → MangaBaka. Data: CC BY-NC-SA 4.0 (attribution shown).
- **AniList** GraphQL, no auth, `X-RateLimit-Limit: 30`/min. `coverImage.extraLarge` is resized art.

## 2. Module layout

```
core/                         pure Kotlin/JVM, unit-testable, no Android APIs
  model/                      SourceSeries, SourceChapter, SeriesMetadata, CoverCandidate …
  normalization/              ChapterLabelParser, ChapterIdentity (lossless numbers), TitleNormalizer
  identity/                   IdentityResolver (ids → titles → qualifiers → format/year/status)
  reconciliation/             ChapterReconciler (canonical chapters, all releases preserved)
  completeness/               CompletenessAnalyzer (confirmed / possible / untranslated / numbering)
  ranking/                    SourceRanker, ChapterQualityAnalyzer, ProviderHealth (circuit breaker)
  covers/                     CoverResolver
  quality/                    ImageProbe (dimensions from the first bytes, Range requests)
  fallback/                   ReaderResolver (per-chapter selection), FallbackInterceptor (image-level)
  metadata/                   MangaBakaClient, AniListClient, MetadataResolver
  sources/                    SourceAdapter, SourceRegistry, adapters/{MangaDex,WeebCentral,MangaPill,MangaKatana,MangaRead,Mgeko,Atsumaru,Hiperdex,OmegaScans,ManhwaRead}
  cache/                      CacheManager over a KeyValueStore
  Aggregator.kt               orchestration used by the extension
extension/                    Android app module: HttpSource + ConfigurableSource glue, settings, icon
buildSrc/                     signed JVM jar packaging (adapted from Keiyoushi)
scripts/publish_repo.py       repo.json / index.pb / index.json / index.min.json generator
```

## 3. Key design decisions

**Stable identities.** The host stores the manga URL and chapter URLs forever (read state, downloads,
trackers). Manga URL = `mb:<MangaBaka id>`, `al:<AniList id>` or `p:<provider>:<id>`; chapter URL =
`<series key>|<language>:<chapter key>`, e.g. `mb:3397|en:c:10.5`. Neither changes when providers are
added, removed or re-ranked.

**Where alternatives live.** Each chapter's full release list is written to `SChapter.memo` (Mihon
persists it). Provider links per series go to `SManga.memo` *and* the extension's own cache. If a host
does not keep memos, `getPageList` rebuilds the chapter's releases from the persisted links — slower,
but correct.

**Chapter identity.** Numbers are kept as `whole` + fraction **string** (`12.1 ≠ 12.10`). Kind (main,
extra, side story, prologue, epilogue, oneshot, special, notice), part, season and volume are parsed
separately; words in a chapter's *title* never change its kind. Volume is only part of the key for a
provider detected to restart numbering each volume. Unparseable labels keep their own text as identity.
Different languages never merge.

**Deduplication never deletes.** Grouping only decides what is *shown*; every release stays attached.
Conflicting titles under one key, or a provider whose numbering is offset (detected by aligning chapter
titles), lower release confidence: such releases are used only as failure fallback, never chosen on quality.

**Series identity.** Database ids decide when present (agree → merge, disagree → reject). Otherwise the
best title pair across all variants must score ≥ 0.90, with penalties for edition qualifiers
("Novel", "Volume Version", "Colored", "Doujinshi"…), novel-vs-comic, year gaps and short titles. Two
equally good candidates from one provider → no automatic link (ambiguous). Users can link manually.

**Per-chapter quality.** Cheap ranking first (preference, language, official, provider health,
confidence). With optimisation on, the top 2–3 candidates' page lists are fetched in parallel and 1–2
images per candidate are probed with a 48 KB Range request for dimensions and file size. Completeness
dominates the score; duplicate pages and unreachable first pages are penalised. The decision is cached.
For long-strip chapters (probed height/width > 2) page counts are not compared: providers slice the same
strip into very different numbers of images (16 to 165 for one chapter, measured). A strip is only marked
incomplete when its estimated length (images × probed aspect) is under 40% of the longest candidate's,
since two probes cannot measure it more precisely.

**Image-level fallback.** Image URLs carry a fragment (`#loa-p=…`, never sent to servers). The interceptor
on the extension client retries a failed page with the same index from the next release *only when the
page counts match*, and covers with the next candidate.

## 4. Known limitations

- Providers behind Cloudflare challenges or logins are not supported in the aggregation path.
- Per-series source preferences are expressed as manual links in settings (no custom UI exists).
- Translation accuracy is not assessed.
- Page-level fallback cannot map pages between releases with different page counts.
