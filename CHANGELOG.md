# Changelog

## 1.0.0 - Complete
- Optional sign-in cookies for videos that ask you to sign in: import cookies.txt, only YouTube/Google cookies kept,
  private storage, removable (`CookieFile`, `CookieStore`, tests).
- Removed 94 old strings of the removed integrations (they also clashed with the new `yt_*` names); the About text now
  describes this app. A script check confirms every referenced string resource exists and none is defined twice.
- README rewritten.

## 0.7.0 - Phase 6: subtitles
- `DownloadSpec.subtitleLangs`; `FormatBuilder` adds `--write-subs --write-auto-subs --sub-langs --embed-subs` for video only.
- `SubtitleLangs.sanitize` keeps plain language codes only.
- Subtitles chip and language field in the format options (single video, playlist and batch); history keeps the choice; list label shows CC.

## 0.6.0 - Phase 5: one Downloads tab
- yt-dlp downloads are listed in the Downloads tab with ids from `YtJobIds` (above the older and new engine ranges).
- Actions routed by id range: cancel, retry, remove, open, delete file and the bulk actions.
- `YtHistoryStore` keeps the last 100 finished downloads across restarts.
- Tests: `YtItemMappingTest`.

## 0.5.0 - Phase 4: several links at once
- The link box takes many links (spaces, new lines or commas); `UrlInput.extractAll` with tests.
- Batch card: one type/quality for all; playlist pages inside a batch are left out with a notice.
- Job list shows the saved file name once known.

## 0.4.0 - Phase 3: playlists and thumbnails
- Playlist pages are listed with `--flat-playlist` (fast); tick videos, choose type/quality, queue them in order.
- Thumbnails in the link preview and in the download list (`RemoteThumbnail`, https only, in-memory cache).
- `PlaylistParser` and `UrlInput.isPlaylist` with unit tests.

## 0.3.0 - Phase 2: background downloads
- `YtDlpService` foreground service (dataSync) with progress notification and Cancel all; stops itself when idle.
- Asks for the notification permission before a download (declining never blocks it).
- `summarize()` helper with unit tests.

## 0.2.0 - Phase 1: yt-dlp engine
- Added youtubedl-android (yt-dlp + Python + ffmpeg), ABIs arm64-v8a and armeabi-v7a only.
- New `ytdlp` package: `FormatBuilder` (format string keeps the height limit in every fallback), `YtDlpClient`, `YtDlpJobs`, `PublicExporter`.
- New YouTube tab replaces the AI Assistant placeholder and is the start screen.
- Unit tests: FormatBuilderTest, YtDlpFormatTest.

## 0.1.0 - Phase 0
- New project forked from Almus Downloader 0.14.0, renamed to `com.almus.ytdownloader`.
- Removed: youtube, tiktok, facebook, integration modules, their dialogs, view models, settings sections, tests and docs.
- Removed guard tests that forbid yt-dlp/ffmpeg (NoTranscodingGuardTest).
- `MiniJson` moved to `util` (still used by the media sources).
