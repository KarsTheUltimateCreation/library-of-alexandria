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
| Multi-source search | MangaBaka (or AniList) + every enabled provider in parallel, bounded concurrency and timeouts |
| Series identification | Shared database ids (AniList / MAL / MangaUpdates / MangaDex) first, then all title variants (English, romanized, native, alternatives) with edition guards: *"Solo Leveling Novel"*, *"(Volume Version)"*, sequels and doujinshi are never merged with the main work |
| Chapter aggregation | One entry per series; chapters from all linked providers merged and ordered |
| Deduplication | Structured chapter identities: `10`, `10.1`, `10.10`, `10 Part 1`, `10 Extra`, `S2 Ch 10`, volume-reset numbering are all distinct; `Ch. 010`, `Episode 10`, `Chapter 10 [English]`, `Chapter 10 - Reupload` are the same. Alternatives are never discarded |
| Missing chapters | Chapters absent from one provider are filled from another; gaps no provider fills are reported as *possible*, chapters beyond the translated range as *likely untranslated* — nothing is invented |
| Per-chapter quality | Top candidates' page lists and a few image headers (48 KB range requests) are compared: completeness first, then resolution/compression, reliability, preference |
| Fallback | A failing provider → next release; a failing page image → same page from another release with the same page count |
| Covers | MangaBaka raw art (known dimensions), MangaDex original uploads, AniList, provider thumbnails — scored by resolution, bytes/pixel (catches upscales and over-compression), aspect, authenticity, original-language edition; fallback to the next candidate if loading fails |
| Metadata | MangaBaka and AniList (titles, status, original chapter count, ids), cached, rate-limited, optional |

Bundled providers: **MangaDex** (API, multilingual), **WeebCentral** (EN), **MangaPill** (EN).
See [Adding a provider](docs/ADDING_SOURCES.md).

The series description ends with a short report: linked sources, recovered chapters, possible gaps,
untranslated range and metadata attribution.

## Settings

All available in the extension's native settings (grouped by prefix, since extension settings have no
categories):

- **Covers** – high-quality covers, cover provider, prefer original artwork, automatic cover fallback
- **Quality** – quality optimization, mode (Best / Balanced / Data saver — uses MangaDex data-saver images), prefer original resolution, quality-based switching
- **Chapters** – deduplication, missing-chapter recovery, source fallback, specials, alternative translations, prefer official, preferred source, languages
- **Metadata** – AniList, MangaBaka, preferred provider, completeness checking
- **Sources** – enabled providers, provider order, manual series links (`mb:3397 = weebcentral:<id>, mangapill:-`)
- **Performance** – concurrency, timeout, metadata cache duration, cover cache, clear cache

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
Provider parsing for WeebCentral and MangaPill and the JAR packaging are adapted from
[keiyoushi/extensions-source](https://github.com/keiyoushi/extensions-source) (Apache-2.0).
Metadata from [MangaBaka](https://mangabaka.org) (MangaBaka-original data CC BY-NC-SA 4.0) and
[AniList](https://anilist.co). Library of Alexandria hosts no content.
