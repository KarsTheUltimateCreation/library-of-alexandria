<p align="center"><img src="docs/icon-512.png" width="96" alt=""></p>

# Library of Alexandria

**One manga. One unified chapter list. Multiple underlying sources.**

Library of Alexandria is a single Mihon / Tachiyomi-compatible extension that searches several manga
providers at once, recognises the same series across them, merges their chapter lists into one
de-duplicated list, fills gaps from whichever provider has the missing chapters, picks the best release
per chapter, and chooses the best authentic cover art. It runs entirely on your device; the only
infrastructure is a static GitHub repository.

> Status: early release (1.6.x). See [Compatibility](docs/COMPATIBILITY.md) for exactly what has and has
> not been verified on each app.

## Install

1. In Mihon: **More → Settings → Browse → Extension repos → Add**, and paste
   ```
   https://raw.githubusercontent.com/KarsTheUltimateCreation/library-of-alexandria/repo/index.pb
   ```
   In Tachimanga: add the same URL as an extension repository (see Tachimanga's
   [Extension Repositories guide](https://tachimanga.app/help/guides/repositories.html)).
2. **Browse → Extensions**, install **Library of Alexandria**.
3. Open its settings (gear icon next to the source) to adjust behaviour — every enhancement is optional.

Requires an app supporting extension lib **1.6** (Mihon 0.21+, Tachimanga 4.16+).

## What it does

| Feature | How |
|---|---|
| Multi-source search | MangaBaka (or AniList) + every enabled provider in parallel; the results page never waits more than 6 s for a provider |
| Speed | Per-request network timeouts adapt to each provider's measured latency and start only when a request is actually sent; a failing provider cools down (2 → 30 min); a service's `Retry-After` (seconds or HTTP date) pauses every caller of that service instead of being retried early; recent chapter lists are reused instead of refetched; identical concurrent requests are merged |
| Source discovery | Opening a series searches the most promising healthy providers first (past hit rate, health, content-type specialty); the rest are searched only when chapters are missing, a linked source fails, or *Search every source* is on |
| Series identification | Shared database ids (AniList / MAL / MangaUpdates / MangaDex) first, then all title variants (English, romanized, native, alternatives) with edition guards: *"Solo Leveling Novel"*, *"(Volume Version)"*, sequels and doujinshi are never merged with the main work |
| Chapter aggregation | One entry per series; chapters from all linked providers merged and ordered |
| Deduplication | Structured chapter identities: `10`, `10.1`, `10.10`, `10 Part 1`, `10 Extra`, `S2 Ch 10`, volume-reset numbering are all distinct; `Ch. 010`, `Episode 10`, `Chapter 10 [English]`, `Chapter 10 - Reupload` are the same. Alternatives are never discarded |
| Missing chapters | Chapters absent from one provider are filled from another; gaps no provider fills are reported as *possible*, chapters beyond the translated range as *likely untranslated* — nothing is invented |
| Per-chapter quality | Top candidates' page lists and a few image headers (48 KB range requests) are compared: completeness first, then resolution/compression, reliability, preference |
| Fallback | A failing *or timing-out* provider → next release; a stalled candidate never holds up the per-chapter comparison; a failing page image — including one whose download breaks off after it started — → same page from another release with the same page count |
| Offline resilience | Provider links and the last known chapter list of each linked provider survive app restarts (bounded); an outage keeps the chapters you already have instead of removing them |
| Covers | MangaBaka raw art (known dimensions), AniList, provider thumbnails — scored by resolution, bytes/pixel (catches upscales and over-compression), aspect, authenticity, original-language edition; fallback to the next candidate if loading fails |
| Metadata | MangaBaka and AniList (titles, status, original chapter count, ids), cached, rate-limited, optional |

English only, 18+ content included. Bundled providers:

- **Series:** MangaDex (API, English chapters only; used for chapters, never for covers or standalone results),
  WeebCentral, MangaPill, MangaKatana, MangaRead, mgeko (mostly manhwa/manhua) and Atsumaru (API, several
  scanlation groups per chapter); since 1.6.6 also Asura Scans (API, manhwa) and MangaTown (large manga catalogue).
  Provider ids added from 1.6.6 on are namespaced `loa_` (`loa_asurascans`, `loa_mangatown`).
- **Adult series:** Hiperdex, Omega Scans, ManhwaRead, Hentai20 and Hentai2Read — searched to link a series only
  when it is adult content. ManhwaRead and Hentai2Read sit behind Cloudflare and need the app to solve the challenge.
- **Galleries** (doujinshi, one-shots): NHentai, Hitomi, E-Hentai and Pururin — English galleries only, shown as
  their own search results (one chapter each), never searched to link catalogue series.

See [Adding a provider](docs/ADDING_SOURCES.md).

Search results are ordered by how well each title matches the query (exact titles first); provider hits that
only loosely resemble the query are not shown. The filter sheet (sort, type, status, content rating, genres
with include/exclude) searches the MangaBaka catalogue.

The series description ends with a short report: linked sources, recovered chapters, possible gaps,
untranslated range and metadata attribution.

## Settings

Eleven settings, all optional. Deduplication, missing-chapter recovery, source fallback, timeouts, request pacing,
concurrency and caching are always on and tune themselves.

- **High-quality covers**
- **Reading quality** – Best / Balanced / Data saver / Fastest (first working source, no comparison)
- **Preferred source**, **Special chapters**, **Separate translations**
- **Metadata** – MangaBaka + AniList / MangaBaka only / AniList only; **Source report** in the description
- **Sources** – enabled providers; **Search every source** (thorough discovery when a series is opened);
  **Manual series links** (`mb:3397 = weebcentral:<id>, mangapill:-`)
- **Clear cache**

Search also accepts identity keys directly: `mb:3397`, `al:105398`, `p:mangadex:<uuid>`.

## Development

```
core/        host-independent engine (pure Kotlin/JVM, unit tested)
extension/   Mihon/Tachiyomi glue (HttpSource, settings)
buildSrc/    signed JVM-jar packaging
scripts/     repository index generator, icon, signing key helper
docs/        architecture & research, compatibility, releasing, adding providers
```

```sh
./gradlew :core:test                               # offline unit tests
LOA_LIVE=1 ./gradlew :core:test --tests '*Live*'   # live tests against the real services
./gradlew :extension:assembleRelease               # APK + JAR
```

Read [Architecture](docs/ARCHITECTURE.md) for the research findings and design, and
[Releasing](docs/RELEASING.md) for signing and publishing.

## Credits and licence

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
Provider parsing for WeebCentral, MangaPill, MangaKatana, MangaRead (Madara), mgeko, Atsumaru, Hiperdex (Hiper), Omega Scans (HeanCMS), ManhwaRead, Asura Scans and MangaTown and the JAR packaging are adapted from
[keiyoushi/extensions-source](https://github.com/keiyoushi/extensions-source) (Apache-2.0).
Metadata from [MangaBaka](https://mangabaka.org) (MangaBaka-original data CC BY-NC-SA 4.0) and
[AniList](https://anilist.co). Library of Alexandria hosts no content.
