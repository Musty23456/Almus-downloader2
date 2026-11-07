# ALMUS YOUTUBE DOWNLOADER

Android app (Kotlin, Jetpack Compose) that downloads video and audio from links with **yt-dlp** (through
youtubedl-android: yt-dlp + Python + ffmpeg inside the app). Built from the Almus Downloader base: interface,
queue, Room database, file manager and download service. Started from the Termux script
`ALMUS_YOUTUBE_DOWNLOADER_PHASE4_MULTI.py`.

For personal use. Downloading from YouTube may go against its Terms of Service: use it for your own videos,
Creative Commons content or content you have permission to save. Not meant for the Play Store.

## What it does
- **YouTube tab** (start screen): paste a link, *Check link*, choose Video or Audio.
  - Video: quality list read from the real video (or *Best*), MP4 or WEBM, optional embedded **subtitles**
    (languages such as `en, ha`).
  - Audio: MP3, M4A, WAV or Original (no conversion).
  - **Playlist pages** (`youtube.com/playlist?list=...`): tick the videos, one choice for all.
  - **Several links at once**: paste many links (one per line), one choice for all.
  - Thumbnails, progress, Cancel, Open. Two downloads run at the same time.
- **Downloads tab**: yt-dlp downloads appear together with the other downloads (status tabs, Cancel, Retry, Open,
  Remove, Delete file, bulk actions). The last 100 finished ones are kept after a restart.
- **Background downloads**: a foreground service keeps downloads running with one progress notification
  and *Cancel all*.
- **Files** go to `Downloads/Almus YouTube` (Android 10+). On Android 9 and older they stay in the app folder.
- **Engine card**: *Update yt-dlp* (YouTube changes often; update when a link stops working) and optional
  sign-in **cookies** for videos that ask you to sign in.
- The other tabs (Home, Browser, Files, Settings) come from Almus Downloader and download direct http/https
  files with the app's own engine.

## Cookies (optional)
A cookies.txt file is a login to your account. The app keeps only YouTube and Google cookies from the file,
stores them in its private storage (not backed up, never logged), and you can remove them in the Engine card.
Use a spare account if you can: YouTube may limit accounts used with download tools.

## Build
- GitHub Actions: `.github/workflows/android-build.yml` builds the debug APK and runs the unit tests.
  Release signing uses the repository secrets described in the workflow.
- Locally: `./gradlew testDebugUnitTest assembleDebug` (JDK 17, Android SDK 35).
- Only `arm64-v8a` and `armeabi-v7a` are included (phones). The APK is large because it carries Python and ffmpeg.

## Code map
- `ytdlp/` - the yt-dlp side: `YtDlpClient` (the only file that touches the library), `FormatBuilder`,
  `YtDlpJobs` (queue), `YtDlpService` (foreground service), `PublicExporter`, `PlaylistParser`, `CookieFile`,
  `CookieStore`, `YtHistoryStore`.
- `ui/screens/YouTubeScreen.kt`, `ui/state/YtDlpViewModel.kt` - the YouTube tab.
- `model/YtItemMapping.kt` and `ui/state/DownloadsViewModel.kt` - yt-dlp jobs inside the Downloads tab
  (ids from `YtJobIds`: positive = older engine, negative = new engine, above 2^40 = yt-dlp).
- `engine/`, `data/`, `download/`, `files/`, `platform/` - from Almus Downloader (direct-link engine, Room, link detection).

## Licence note
youtubedl-android is GPL-3.0. Keep this project's source available if you share the app.
