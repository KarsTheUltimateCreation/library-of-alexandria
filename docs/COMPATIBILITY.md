# Compatibility and validation status

Last updated for version **1.6.8** (November 4, 2026). This page separates what was actually tested from what is
only expected to work. Nothing here is claimed as tested unless it was run.

## Validation performed

| Level | What | Result |
|---|---|---|
| Unit tests (offline) | 198 tests (plus 6 opt-in live tests and the opt-in request benchmark). 1.6.8 adds: MangaFire request signatures checked against vectors from an independent implementation the live API accepted; parsing of MangaFire, MangaTaro (incl. its hourly chapter token), Flame Comics and Manga Ball (only the directly readable mirror's English chapters); MangaFire's page-1-only refreshes with a daily full read; the per-series cap (5 linked, rest as spares), spare promotion on a failing source without any search, trimming older over-linked series only of sources with no chapter of their own; outage retention keeps only chapters whose releases all belong to the unavailable sources (stale rows from older numbering are let go); cover selection measuring every candidate in parallel under one budget, slow probes as *unknown*, remembered sizes needing no request; author names with a native form in parentheses. All 1.6.7 tests (provider-only operation with zero metadata requests, corroborated identity, persistence, request budgets, cooldowns, cancellation) still pass | **Pass** |
| Live tests (JVM) | 6 opt-in tests against the real sites, incl. the four new providers: search → details → chapters → pages → image download, cover download | **Pass** (Nov 4 2026) |
| Live request benchmark (JVM) | `RateLimitBenchmark`: the same scripted session — 14 searches, opening each first result, four library updates of those 14 series 0 h / +8 h / +16 h / +24 h apart (simulated clock, 4 at a time), the same searches again an hour later, 2 searches during a library update, reading 2 chapters of 4 series (3 images each) — run against **1.6.6** and **1.6.7** with fresh state; every HTTP request counted per host | See [the comparison](#rate-limit-comparison-166-vs-167) |
| Build validation | APK (versionCode 106007, lib 1.6) and JVM JAR assemble. JAR inspected: only `alexandria.*` and the extension package (no kotlin/kotlinx/OkHttp/Okio/Jsoup/extensions-lib/Injekt classes); Java 17 bytecode (AGP's generated `R` classes: Java 8); no new runtime dependency | **Pass** (local build is unsigned; the published APK/JAR are signed by the release workflow) |
| Emulator (Mihon) | Mihon v0.21.3 on an Android 15 arm64 emulator — performed for **1.6.1** only | **Not repeated for 1.6.7** |
| Physical device | — | **Not tested** |
| Tachimanga (iOS) | — | **Not tested** (no iOS device/simulator runtime available; Tachimanga is closed source). Nothing on this page about Tachimanga is confirmed behaviour |

## Rate-limit comparison (1.6.6 vs 1.6.7 vs 1.6.8)

Live run on Nov 4 2026, same machine and network, same script (`RateLimitBenchmark`), fresh state, default settings
of each version. Third-party sites vary from run to run; this is one run each, not a statistical study.

| | 1.6.6 (18 sources) | 1.6.7 (18) | 1.6.8 (22, cap 5) |
|---|---:|---:|---:|
| Total HTTP requests | 1,614 | 763 | 828 |
| Requests to metadata services (MangaBaka / AniList, APIs and CDNs) | 37 | **0** | **0** |
| HTTP 429 responses | 1 (nhentai) | **0** | **0** |
| Requests refused locally by a service cool-down (`asked to wait`) | 3 (nhentai) | **0** | **0** |
| Provider calls that timed out waiting for a local rate-limit slot | 3 (WeebCentral) | **0** | **0** |
| Hitomi index requests (searched on every query in 1.6.6) | 391 | 123 | 123 |
| Each library update after +8 h / +16 h / +24 h | 25.5 s / 25.9 s / 28.0 s | 9.1 s / 8.1 s / 7.9 s | 8.0 s / 8.1 s / 7.9 s |
| Searches during a library update | 24.4 s | 6.9 s | 8.5 s |
| User-visible failures | 0 | 0 | 0 |

### Stress tests (1.6.8)

*Offline* (`StressTest`, opt-in `LOA_STRESS=1`): six simulated sites behind a local server that enforce their own
rate limits (429 + `Retry-After`), one stricter than the extension's configured rate, one with 10 % server errors, one
answering 25 % of requests after 4 s, one going fully down mid-run; a 120-series library; 40 concurrent searches,
opening every series 8 at a time, a library update concurrent with 20 searches and 30 chapter openings, an update
during the outage, 40 updates cancelled mid-flight and rerun, two library updates at once.

| | first run | after fixes |
|---|---:|---:|
| 429s from the stricter site | 455 of 1,064 (43 %) | 10 of 575 (2 %) |
| 429s from the slow site (limit above ours) | 115 | 0 |
| Series briefly over the 5-source cap | 0 | 0 (2 in an intermediate run, fixed) |
| Errors surfaced to callers / chapter lists that shrank / series without chapters | 0 / 0 / 0 | 0 / 0 / 0 |

Fixes it led to: the rate-limit token is now taken only once a connection slot is held (tokens piled up while slow
responses held the slots and went out as a burst); the request rate adapts — each 429 burst halves that service's
rate (floor 10 %), +50 % after every 30 s without one; at the cap, a failing source swaps places with a spare instead
of being joined by it.

*Live* (`RateLimitBenchmark` with `LOA_BENCH_SCALE=2`): 30 searches, a 30-series library, library updates 8 at a time,
searches during an update. 1,735 requests, 0 metadata requests, 0 user-visible failures; library updates 11–13 s.
One 429 came from nhentai's anonymous search (10/min) after rapid searches widened to gallery sites; its limiter was
lowered to 6/min.

1.6.8's extra requests come from the four new sources (MangaFire 57, Manga Ball 31, MangaTaro 15, Flame Comics 2); a
first 1.6.8 build made 871 before MangaFire's refreshes were limited to page 1.

A first 1.6.7 build without the busy-provider rule still had 4 WeebCentral queue timeouts (WeebCentral allows 3
requests per 6 s); routing optional work around a provider whose queue is already ≥ 3 s long removed them.

## Mihon

Target: Mihon **0.21.x** (extension lib 1.6). Verified by reading Mihon's source: extension loading
rules, Injekt bindings, memo persistence, image download path and repository parsing (see
[ARCHITECTURE.md](ARCHITECTURE.md)).

### Emulator test (Nov 3 2026)

Mihon **v0.21.3** (official arm64 release APK), Android 15 emulator (Google APIs, arm64). The repository
generated by `publish_repo.py` was served from the host over `adb reverse`, so the real install path was used.

| Step | Result |
|---|---|
| Add repository by its `index.pb` URL | ✅ Mihon parsed the gzipped protobuf index and listed the store |
| Extension listing (name, version 1.6.1, "Mixed" warning, icon) | ✅ |
| Install from the repository | ✅ APK downloaded and installed |
| Trust | ✅ auto-trusted (signing fingerprint in the index matched); no "Untrusted" prompt |
| Extension loads, source appears under Browse | ✅ |
| Native settings screen | ✅ all switch/list/text preferences render |
| Popular (MangaBaka popularity) | ✅ after fixing a sort-order bug found during this test |
| Search "solo leveling" | ✅ unified entry; sequels, book/volume editions and doujinshi listed separately |
| Series details (Berserk) | ✅ metadata, alt titles, linked-sources report |
| Unified chapter list | ✅ titles, dates, per-chapter provider list (MangaDex · WeebCentral · MangaPill · MangaKatana · MangaRead · mgeko · Atsumaru) |
| Reading | ✅ pages load in the reader; per-chapter comparison ran on-device (logcat); moving into the next chapter works |
| Add to library | ✅ entry kept with 201 chapters |
| Library update (pull to refresh) | ⚠️ **not confirmed** — no errors logged, but the run could not be observed from the test harness |
| Image-level fallback on a real failing image | ⚠️ **not exercised on device** (covered by JVM tests of the interceptor) |

## Tachimanga

Target: Tachimanga **4.16+** (extension lib 1.6 support per its changelog). The repository publishes the
`index.pb` + `jarUrl` layout Keiyoushi uses for the same apps. Expected behaviour and safety nets:

| Capability | Expectation | If unsupported |
|---|---|---|
| Install from `index.pb` | Same format as Keiyoushi's repo | — |
| Lib 1.6 suspend API | Supported since 4.16 per changelog | — |
| `SManga.memo` / `SChapter.memo` | Unknown | Provider links come from the extension's own cache; if that was lost too, from the release memos in chapter records; if those are missing as well, from the title (two sources must corroborate) or a manual link |
| Search page 2 (`hasNextPage`) | Expected (standard paging) | Without it, the remaining providers are still asked on page 1 when the main ones find little, and *Search every source* asks all of them |
| Images loaded through the extension's OkHttp client | Unknown | Page-level and cover-level fallback silently do nothing; chapter-level fallback (in `getPageList`) still works |
| `getSharedPreferences("source_<id>_cache")`, `("source_<id>_chapters")` | Expected (standard API) | Falls back to an in-memory cache: links and chapter lists are not kept across restarts, so the first opening after a restart refetches them |
| Image loading after the headers (body stalls / breaks off) | Recovered only if images go through the extension client | The host shows its own image error; *retry* in the reader asks again |
| Preference widgets | Switch / List / MultiSelect / EditText are standard extension widgets | — |
| `OnPreferenceChangeListener` (clear-cache action) | Unknown | The action may not run; use the app's own "clear data" instead |

### Device checklist (1.6.8, Tachimanga on iPhone)

Record the Tachimanga version and iOS version first. "Pass" means seen on the device.

1. **Update / install** – update to *Library of Alexandria 1.6.8* (or install it), open its settings: *External
   metadata* shows **Off**; all settings render.
2. **Migration of existing entries** – open a series that was in the library before updating (an `mb:` entry):
   chapters appear, read state is kept, the description still shows its earlier text, *Sources:* lists providers.
   Use Tachimanga's *Migrate* on another source's entry and pick Library of Alexandria as the target: the search
   inside migration finds the series (provider-only results), and migrating keeps read progress.
3. **Search** – search "solo leveling" and "berserk": results within ~6 s, one entry per series, novels/sequels
   separate. Scroll to the end of the results: a second page may load with more sources' entries.
4. **Details** – open an entry: description, author, genres, status and cover are filled; the description's report
   ends with *Details: description from …*. No entry shows MangaBaka/AniList as a source unless you enabled it.
5. **Reading** – open a chapter and the next one; pages load.
6. **Fallback** – set *Preferred source* to one provider, open a chapter; turn Wi-Fi off and on mid-chapter, reopen:
   the chapter still opens (another release if needed), no endless spinner.
7. **Settings changes** – change *Preferred source* or *Reading quality*, reopen an already-read chapter: it comes
   from the new choice. Set *External metadata* to *MangaBaka + AniList* and search: catalogue results appear; set it
   back to *Off*.
8. **Library update / rate limits** – run a library update twice in a row and once after a few hours: no "HTTP 429",
   "asked to wait" or "waiting for a local request slot" errors; the second run is quick.
9. **Restart persistence** – force-quit, relaunch offline, open a library series: chapters are still there. Back
   online, open a chapter without refreshing: it opens without a long delay. Search a library series again: it
   shows as the same library entry (not a duplicate).
10. **New sources** – search "solo leveling": open the entry and check *Sources:* (MangaFire, MangaTaro may appear);
    search "omniscient reader" (Flame Comics). Open a chapter served by each.
11. **Covers** – open a few series: the cover is sharp (not a small thumbnail). Re-open one: it shows at once.
12. **Duplicates gone** – on the series from the earlier screenshot, pull to refresh: 36 chapters instead of 41
    (only if Tachimanga removes chapters the extension no longer lists; note what happens).

Please report Tachimanga results in an issue so this table can be updated with facts.

## Provider notes

| Provider | Access | Notes |
|---|---|---|
| MangaFire *(1.6.8, `loa_mangafire`)* | JSON API, signed requests | Large catalogue; English chapters only, several translations per chapter kept as separate releases. Requests carry the site's `vrf` signature (ported from Keiyoushi, verified live). MangaFire can demand a captcha (`403 captcha_required`) that only a WebView solves: not supported, so it then fails like any outage and other sources serve the chapter. The full-size poster is used as cover. Refreshes read page 1 only, a full list once a day. Verified live. |
| MangaTaro *(1.6.8, `loa_mangataro`)* | JSON (WordPress) | Search returns authors and alternative titles. The chapter list needs an hourly token tied to the server clock (corrected from its `Date` header). Verified live. |
| Flame Comics *(1.6.8, `loa_flamecomics`)* | Next.js data routes | Manhwa scanlation group (~170 series). No search endpoint: the series list is fetched once per 30 min and searched locally. Build id re-read after a site deploy. Full-size covers. Verified live. |
| Manga Ball *(1.6.8, `loa_mangaball`)* | JSON API | Only English chapters on mirror `cdn_2` are listed: mirror `cdn_1` answers every image with a Cloudflare browser challenge (403 "Just a moment…", Nov 4 2026). Most of the library is on `cdn_1` (One Piece: 0 of 3,160 English entries readable; Solo Leveling: 135 of 728), so its contribution is small. Verified live for `cdn_2`. |
| Asura Scans *(1.6.6, `loa_asurascans`)* | JSON API + Astro props | Manhwa/manhua group. Premium and early-access chapters are left out. Chapters whose pages are scrambled tiles fail on purpose (descrambling needs Android bitmaps), so another provider serves them; 17 of 18 sampled chapters across six popular series were plain (Nov 4 2026). Verified live. |
| MangaTown *(1.6.6, `loa_mangatown`)* | HTML | Large general catalogue. Numbers and volumes come from chapter URLs. Paged chapters are resolved one viewer page at a time while reading (no per-chapter quality probe). Images need the site Referer. Its numbering sometimes differs from other providers (Berserk: +15); such releases are detected and used only as fallback. Verified live. |
| MangaDex | Public API | Used for chapters only: its covers are not used and its unmatched search hits are not listed (its CDN serves a placeholder image to requests carrying another site's Referer). English chapters only; search and browse only return series that have English chapters. Licensed series often have none; chapters listed but missing on MangaDex@Home are excluded or fall back. Data-saver images used in Data Saver mode. |
| WeebCentral | HTML | 1 request / 2 s (same pacing as the Keiyoushi extension). Tracker links give AniList/MangaUpdates ids. |
| MangaPill | HTML | No alternative titles on detail pages (only on search cards). Images need the site Referer. |
| MangaKatana | HTML | Large EN catalog with many native-language alt titles. No content type field (manhwa/manhua inferred from genres only). Page URLs are short-lived tokens, so pages are resolved at read time. |
| MangaRead | HTML (WordPress Madara) | Chapter list via `ajax/chapters/`. Site search also matches descriptions, so hits sharing no title word with the query are dropped. Cover is the full-size WordPress upload of the search thumbnail. |
| mgeko | HTML | Mostly manhwa/manhua; few long-running Japanese series. Site search is a literal title substring match, so the longest punctuation-free part of the query is sent. Notice posts ("spoiler", "announcement") are dropped. |
| Atsumaru | JSON API | Detail pages carry AniList / MyAnimeList / MangaBaka / MangaUpdates ids (exact identity). Most chapters have uploads from several scanlation groups, each kept as its own release. Adult and novel entries are excluded from search. Some page images are AVIF. |
| Hiperdex *(18+)* | tRPC JSON API | hiperdex.tv. The API answers 401 until the session cookie from a normal home-page visit is set (done automatically). The page-list endpoint refuses non-browser clients, so pages come from the chapter page's first images plus the chapter's page count (files are numbered `01..N`). Covers and pages need the site Referer: browse covers rely on the extension client's image-header interceptor (verified on the JVM; not verified in Tachimanga). |
| Omega Scans *(18+)* | HeanCMS JSON API | Coin-locked (paid) and scheduled chapters are left out. |
| ManhwaRead *(18+)* | HTML | Behind a Cloudflare challenge: works only when the app's client solves it (Mihon's WebView interceptor). **Not verified** — the challenge blocks automated testing; selectors follow the Keiyoushi extension. |
| Hentai20 *(18+)* | HTML (MangaThemesia) | Images listed in the chapter page's `ts_reader.run(...)` data. Verified live (search, details, chapters, pages, images). |
| Hentai2Read *(18+)* | HTML | Behind a Cloudflare challenge like ManhwaRead. **Not verified** — selectors follow the Tachiyomi Hentai2Read extension. |
| NHentai *(18+, gallery)* | JSON API v2 | English galleries only (`language:english`). Anonymous search is limited to 10/min, so it is only searched for user searches. Verified live. |
| Hitomi *(18+, gallery)* | Static index (range requests) | The site's in-browser B-tree search is reimplemented; results are intersected with the English gallery list. Very common words ("the") are skipped. Index nodes are cached, so later searches are faster (first search ~6 s, then ~3 s). Image URLs follow `gg.js`, re-read every 30 min. Needs the `hitomi.la` Referer (covers rely on the image-header interceptor). Verified live. |
| E-Hentai *(18+, gallery)* | HTML + gdata API | English galleries only (`language:english$`). Pages are viewer pages resolved one at a time while reading (`getImageUrl`). Rate-limited to 2 requests/s to avoid E-Hentai's temporary bans. Verified live. |
| Pururin *(18+, gallery)* | HTML | English-only site. Images are numbered in the gallery directory, so a chapter needs two requests. Verified live. |
