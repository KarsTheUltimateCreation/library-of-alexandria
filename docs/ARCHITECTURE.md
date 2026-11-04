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
- **Re-checked Nov 4 2026 (1.6.6):** `Nyora-Manga/nyora-mihon`, `nyora-mihon-extension-porter` and `nyora-shared`
  still declare **no license** (GitHub API: `license: null`), so none of their code may be reused.
  `Kotatsu-Redo/kotatsu-parsers-redo` (master `68f3fed`, Nov 1 2026; the parser revision Nyora pins) is **GPL-3.0**
  and needs `org.json` and `androidx.collection` at runtime in addition to the host libraries, plus KSP-generated
  code. Nyora's Mihon host relies on ActivityThread reflection, WebView user agents, Android bitmaps and a
  Cloudflare interceptor located by class name. Decision (maintainer, Nov 4 2026): stay Apache-2.0 — no Kotatsu or
  Nyora code is bundled. Coverage grows through portable adapters instead (Asura Scans, MangaTown in 1.6.6).
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
  sources/                    SourceAdapter, SourceRegistry, adapters/{MangaDex,WeebCentral,MangaPill,MangaKatana,MangaRead,Mgeko,Atsumaru,Hiperdex,OmegaScans,ManhwaRead,Hentai20,Hentai2Read,NHentai,Hitomi,EHentai,Pururin}
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

**Search grouping.** Provider hits form one result when one's main title is the other's main *or alternative*
title (same edition qualifiers; compact titles under 8 characters only main-to-main), and nothing contradicts. Author
lists contradict only when no name is shared or near-identical (≥ 80 % edit similarity: "Mak Ellaha" ~ "Markellaha"),
and not at all when the hit already shares authors with another member of the result (sites romanise Korean names
differently). Placeholder credits ("Bot", "Updating", "N/A") are ignored, and for manhwa and manhua differing Latin
credits are never a contradiction (no single romanisation: "Supp" / "Seop-I"). A site's second entry with exactly the
same title as one already in a result (MangaFire and Atsumaru list some series twice, Hitomi uploads chapters as
same-named galleries) is not shown; within a site, the entry whose own title best answers the search is placed first,
so an edition ("Book Version", whose aliases include the plain title) cannot take the plain entry's place. A result is
named after the member title closest to the query (word for word for aliases: some sites list run-together search
aliases), since hosts match migrations on it.

**Numbered parts as one series (`VolumeSeries`).** Doujinshi series ("Imouto wa Mesu Orc", "… 2" … "… 8") are separate
entries on gallery sites, Hentai2Read and Manga Ball ("[H]" titles). `VolumeGroupingAdapter` wraps those providers and
folds entries whose titles (any alias, gallery tags removed) share a base and end in a part number 1–99 into one entry
`vol~<base words>`; part n's chapter becomes chapter n (several chapters: n.1, n.2…), keeping the part's own ids so
reading goes straight to the provider. Refreshing the chapter list searches the base title again, which picks up new
parts. Part series on different sites with a shared base title join one search result and are linked when it is opened.

**Empty searches.** A first search page with no results asks every enabled provider that has not answered (busy,
cooling down or cut off by the 6 s deadline) with a 15 s deadline before reporting nothing, and an empty page is not
cached. Bulk migration searches while the library updates; "no results" there made the host skip titles a manual search
then found.

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

**Timeouts, pacing and cancellation (1.6.6).** Every provider operation runs under an overall ceiling (20 s) and
a `RequestBudget` in the coroutine context. Each network exchange gets its own timeout (adapted to the provider's
measured latency, ≥ 6 s) that starts only when the request is actually sent and covers DNS, connect, headers *and*
body (OkHttp call timeout); the body is read inside the call, so cancelling the coroutine cancels the socket read.
Local waits — a rate-limit token, a service cool-down, one of the 4 concurrent slots per service — count as queue
time: a wait the budget cannot cover fails at once, and a deadline spent purely queueing is not held against the
provider's health. `Retry-After` (seconds or HTTP date) sets a process-wide cool-down for that service; a pause longer
than the remaining budget (or 8 s without one) is not slept: the call fails fast so the caller falls back. Retries
(transient errors only, at most 2) use randomised backoff and must fit the remaining budget. A provider exceeding
its own deadline raises `ProviderTimeoutException`, an ordinary failure; only cancellation of the caller propagates.

**Caching (1.6.6).** Each provider's chapter list is stored compactly (`StoredChapterList`) in its own preference
file (≤ 48 lists, ≤ 150 k characters each; unchanged lists are not rewritten). Host-requested refreshes reuse a list
fetched within the last minute (duplicate calls); internal rebuilds reuse lists up to 30 minutes old; failures and
cool-downs fall back to the last known list. After a restart, page resolution rebuilds the full release list from
the stored lists without network. Confirmed provider links have their own persisted budget (400 series). Chapter
choices are cached under a fingerprint of every selection setting, so changing the preferred source or reading
quality takes effect on the next opening. In-memory state (canonical chapters, alternate page lists, resolutions)
is bounded; identical concurrent chapter-list and page-list fetches share one request.

**Progressive discovery (1.6.6).** Linking a series searches, at first, only the three most promising unlinked
providers (past hit rate, health, content-type specialty, user preference), and none when two are already linked.
After building the chapter list, the remaining relevant providers are searched only if fewer than two linked
providers contribute chapters, a linked provider failed with nothing cached, the metadata's chapter count exceeds
what was found, or the completeness report shows possible gaps. *Search every source* searches all at once.
Searched providers without a match are skipped for a week, as before.

**Provider-only mode (1.6.7, default).** External metadata is off unless the user enables it, and then no request
reaches MangaBaka or AniList — not their APIs, not their image CDNs — on any path (search, popular/latest, filtered
browse, details, matching, cover selection, library refresh, page resolution). `MetadataResolver` returns at once
when both services are disabled, `Aggregator` never calls it, and covers whose candidate provider is a metadata
service are dropped (the cover cache key includes the mode, so artwork chosen by 1.6.x is not reused). This is
covered by a JVM test that runs every workflow against clients that record each request.

- *Search* asks the five most relevant healthy general providers (discovery hit rate, health, preference; fixed
  provider order on ties); when they return fewer than three relevant titles, the others (MangaDex, adult and gallery
  sites) are asked at once, otherwise only when the host asks for page 2. Hits are grouped by `SearchClusterer`:
  same title and edition qualifiers, or a shared id, and nothing contradictory (ids, novel/comic, years > 3 apart,
  different Latin-script authors); never across provider roles; at most one hit per provider. A grouping is a display
  decision only. The other providers in it are *hints* (`Binding.hint`), checked against the provider's details
  when the series is opened and dropped (and remembered as rejected, `rej:` and `SeriesState.rejected`) unless the
  details corroborate the match.
- *Identity* (`IdentityResolver`): a title match (≥ 0.90) confirms only with support ≥ 1 — an author in common
  (word-order and case-insensitive), years within one, a second agreeing title variant, or shared ids; same format or
  same status add 0.25 each and never suffice. Latin-script author lists with no name in common subtract 0.3. Two
  equally good candidates on one provider are still refused.
- *Stable identities*: confirmed links are indexed (`idx:<provider>:<id>` → series key, first writer wins, 2,000
  entries), so search, popular and latest show a provider entry as the series it belongs to, whatever the provider
  order or newly added providers. New series are keyed `p:<provider>:<id>` with the anchor chosen in fixed registry
  order, never the user's order. `mb:`/`al:` keys from earlier versions keep working: their stored record (and its
  MangaBaka attribution) is shown without being refreshed, and their links come from the stored state, the manga
  memo, or — when both are gone — the release memos in the host's own chapter records (`recovered`). Only when none
  survives is the host's title used, and then two providers must corroborate each other (same exact title plus an
  author, year or id) before anything is linked.
- *Details* (`DetailsMerger`): external record (when present) → entry source and manual links → confidence →
  fixed provider order. The first description of ≥ 80 characters wins (else the longest); authors, status, format,
  year and language come from the first source that knows them; genres and alternative titles are unions. The
  description's report names the source of each field.
- *Library updates* (`getMangaUpdate` with `fetchDetails = false`) refresh the two linked providers whose last lists
  reach furthest on every update, and the others when their list is older than 6 h; a pull-to-refresh asks everyone.
  Searches they trigger take tokens from a shared, non-blocking per-provider budget (6/min) and are skipped (not
  queued, not recorded as misses) when it is spent; the every-provider widening runs at most every 12 h per series.
  Provider search results and details are shared for 20 min between search, linking and updates (single-flight).

**Per-series cap (1.6.8).** At most 5 sources are linked (read from and refreshed) per series. Discovery stops at the
cap; a wider search asks at most twice the free slots (*Search every source* asks everyone). Matches beyond the
cap are kept as `SeriesState.spares` (≤ 6) and promoted without searching when a linked source fails or chapters are
missing. A series linked to more sources before the cap existed drops only sources that are the only one for no
chapter, lowest coverage first, never the entry source or manual links.

**Covers (1.6.8).** All candidates without known dimensions (≤ 6) are probed at once (first 64 KB, Range request)
under one 2.5 s budget, concurrently with the chapter list. A probe still running at the budget's end is *unknown*
(neutral), a failed one *unreachable* (penalised). Measured sizes are kept 30 days (`cimg:`), the chosen cover per
series 30 days (`cover:best:`), so search results show the sharpest known cover without a request.

**Outage retention (1.6.8).** During a refresh with an unavailable linked source, a chapter the host has but the
fresh list lacks is kept only if its release memo shows it came solely from unavailable sources.

## 4. Known limitations

- Providers behind Cloudflare challenges or logins are not supported in the aggregation path.
- Per-series source preferences are expressed as manual links in settings (no custom UI exists).
- Translation accuracy is not assessed.
- Page-level fallback cannot map pages between releases with different page counts.
- Image recovery after the headers arrived needs the image to be read by the interceptor first (bounded to 12 MB);
  it only happens on hosts that load images through the extension's client (verified in Mihon's source, **not
  verified for Tachimanga**). Larger images stream as before and cannot be recovered mid-download.
- Recovery alternatives are fetched with `runBlocking` on the OkHttp thread that loads the failing image; under
  extreme saturation of the host's dispatcher a recovery waits for a free slot (bounded by provider timeouts).
- The series preference (last comparison's ranking, reused 3 days) skips the comparison for later chapters of a
  series; it is a speed trade-off and does not re-measure each chapter.
- Provider-only mode cannot use MangaBaka's catalogue: filtered browsing depends on what providers report, there is
  no original-run chapter count (so "likely untranslated" ranges are not reported), and popular/latest lists are the
  providers' own.
- Library updates take a new chapter from a non-leading provider up to 6 h later than from a leading one.
- Service cool-downs are kept in memory only; after an app restart a service is asked again (and re-pauses on 429).
- Asura Scans chapters with scrambled (tiled) pages and premium/early-access chapters are not readable from Asura;
  other providers' releases serve them.
