# Advanced Media Selection (0.14.0)

A clean, full-screen selection flow for audio and video: **choose → review → confirm**. Its one rule: **it only shows what an authorized source really offers.** There is no fixed list of qualities. The eight names (144p … 2160p) are a ladder used for labelling; a rung appears only when a real file has exactly that height.

## The flow
1. **Where choices come from** (see below): the app asks the source's own public service which files exist.
2. **Selection screen** (`MediaSelectionScreen`): title, source, length and number of formats; a *Video / Audio only* switch (only when both exist); one card per real file (quality, format, codec, size); expandable **Format details**; **Save to**; a bottom bar with the current pick and **Review download**.
3. **Checking**: the chosen file's address goes through the app's normal link check (redirects, https, size) before anything is shown as final.
4. **Confirmation screen** (`DownloadReviewScreen`): type, quality, format, length, size (exact or labelled estimate), file name, source, folder, warnings. **Nothing is saved until Download is tapped.** Back returns to the choices with your picks kept.

## Authorized sources
| Source | Address | Where the formats come from |
|---|---|---|
| **Internet Archive** | `archive.org/details/ID` | `archive.org/metadata/ID`: every file with format, size, length, frame size, bitrate. Restricted (borrow-only), dark and private files are refused. Items made of several separate originals (albums) are explained, with how to paste one file's address. |
| **Wikimedia Commons** | `commons.wikimedia.org/wiki/File:...` | MediaWiki API `prop=videoinfo` with `derivatives`: the original plus every version the site made (size, format, bandwidth). Only files on `upload.wikimedia.org` are accepted. |
| **Direct file link** | any single audio/video file | exactly one option: the format and size the server stated. Resolution and length are not stated by a plain link, so they are shown as unknown. |

Everything else (YouTube, TikTok, Instagram, X, Vimeo, Reddit …) offers **no** public list of download formats, so no choices are shown for them, as before. Stream playlists/manifests stay unsupported.

## Not fabricated
- **Quality**: label = shorter side of the real frame. 1920×800 is shown as `800p`, never rounded to a ladder rung. A file with no stated size is `Original quality`.
- **Size**: stated sizes are exact. Without one, size = bitrate × duration and is labelled `about … (estimate)`. With neither: `Size not stated`. After the file check the server's own size replaces any estimate.
- **Duration**: shown only when the source states it; otherwise `Length not stated`.
- **Format details** list only known fields (format, MIME, frame size, codecs, bitrate, sound, version).

## Audio options
| Requested | What is shown |
|---|---|
| Original audio / original format | the source's original audio file, labelled "Original audio · FLAC", saved exactly as provided |
| M4A where supported | an M4A choice **only if the source itself provides an M4A file** |
| MP3 | an MP3 choice **only if the source itself provides an MP3 file** |
| MP3 conversion | **not available in this version.** See below |

**Conversion.** Android has no built-in MP3 encoder and none could be added in this environment (no network, no new libraries), so converting is not offered and no fake button exists. The seam is ready: implement `AudioConverter`, put it in `AlmusContainer.audioConverters`, and a "Convert to MP3/M4A" choice appears, but only for sources whose terms allow processing (`MediaCatalog.conversionAllowed`: an open license on the Archive item, or a Commons file; never for plain links). The download step would still need a post-processing hook (`DownloadPlan.conversion`); until one exists the app refuses such a plan with a clear message.

## Destination
Files can only go below `Download/ALMUS`: that is the only place the download engines write. Choices: **Automatic** (as Settings say), **Main folder**, **Video folder / Audio folder**. Choosing per download needs the **new download engine**; on the older engine only Automatic is offered, with a note.

## Where the code is
`media/` (pure Kotlin): `MediaModels` (variant, catalog, size estimate), `MediaOptions` (ladder, labels, choices, format details, converter seam), `MediaSelection` (selection state, destinations, plan), `MediaSources` (source interface, direct-file source), `sources/InternetArchiveSource`, `sources/CommonsSource`, `android/OkHttpMediaHttp` (two fixed services over https, no redirects, no credentials). UI: `ui/components/MediaSelectionScreens.kt`. Wiring: `DownloadsViewModel` (new add-states `Resolving`, `Select`, `Verifying`, `Review`), `AlmusApplication.mediaSources`, `HomeScreen` and `ConfirmDownloadHost`.

`MediaSourceGuardTest` fails the build if the module gains scraping, stream/manifest handling, sign-in/cookie/token code, hosts other than the reviewed ones, extra request headers, a web view or file writing.

## Tests
`MediaOptionsTest` (ladder, labels, no invented qualities, audio ordering, converter gating, sizes, durations, selection, destinations, plan), `MediaSourcesTest` (matching, Archive and Commons answers incl. private/restricted/album/non-media, estimates, direct files), `MediaSourceGuardTest`.

## Limitations
- **Not compiled, tests not run** (no Kotlin compiler, Android SDK or network here). First CI run is the real check.
- No source was contacted. The MediaWiki `videoinfo` + `derivatives` parameters were confirmed in MediaWiki's API help; field names inside `derivatives` (`src`, `type`, `transcodekey`, `width`, `height`, `bandwidth`) and the Archive metadata fields follow those services' documented/known shapes and may need small adjustment.
- Direct links still show a single option (no resolution or length); that is what a plain file link can honestly say.
- No MP3/M4A conversion (see above). No stream/HLS/DASH support, by design.
