# CastCharm Android

Native Android client for [CastCharm](https://github.com/CastCharm/castcharm), the self-hosted podcast manager. This repository is for people who want to build, inspect, test, or contribute to the app. If you just want to use it, see the [Android page on castcharm.org](https://www.castcharm.org/android.html).

## Get the app

Signed release builds are published on the [Releases page](https://github.com/CastCharm/castcharm-android/releases) as a plain `.apk` you install directly on the phone (Android will ask you to allow installs from your browser or file manager the first time). Each release is signed with the same key, so new versions install over the old one without losing your data. If you use an updater such as Obtainium, point it at this repository and it will pick up new releases on its own. A Google Play listing is planned; until then, GitHub Releases is the official source.

The app needs a CastCharm server to talk to; it does nothing on its own. Server setup is covered in the [installation guide](https://www.castcharm.org/install.html).

The app connects to an existing CastCharm server, authenticates against that server, caches feed and episode metadata locally, streams or downloads episode audio, reports playback progress back to the server, and exposes a Media3 media library for Android Auto.

## Current scope

The Android app currently includes:

- Server login using the CastCharm API
- Feed and episode browsing
- Local Room cache for feeds, episodes, downloads, and playback state
- Episode streaming through the CastCharm server
- Local episode downloads for offline playback
- Prefer-local playback when a downloaded file exists
- Playback progress and played-state sync back to the server
- Media3 / ExoPlayer playback service
- Android Auto media browsing and playback controls
- Jetpack Compose UI
- App settings, including storage/offline-related controls
- Custom and feed-based playlists with drag-to-reorder support (feature toggleable in settings)
- Queue playback: feeds and playlists load as a real player queue that advances on its own, including in Android Auto
- "Listen in chronological order" per podcast (a story or serial): Play resumes where you left off or starts at the oldest unheard episode, synced with the server and the web app
- Pull-to-refresh, filter chips and multi-select batch actions on episode lists
- Wi-Fi-only downloads, skip-silence playback, new-episode notifications, monthly download bandwidth tracking
- OPML import of subscriptions

## Repository layout

```text
app/
├── src/main/
│   ├── java/com/castcharm/android/
│   │   ├── data/
│   │   │   ├── api/             # Retrofit API client, auth/session handling, API models
│   │   │   ├── db/              # Room database, DAOs, entities
│   │   │   └── repository/      # Feed and episode repository logic
│   │   ├── download/            # WorkManager downloads, storage management, scheduling
│   │   ├── player/              # Media3 service, controller, Android Auto media library
│   │   ├── provider/            # Artwork content provider
│   │   ├── sync/                # Background sync helpers
│   │   ├── ui/                  # Compose screens, view models, shared UI components
│   │   ├── CastCharmApp.kt      # Application-level state and API client access
│   │   └── MainActivity.kt      # Single-activity Compose host
│   └── res/
│       ├── drawable*/           # Icons and vector drawables
│       ├── mipmap*/             # Launcher icons
│       ├── values*/             # Strings, colors, themes
│       └── xml/                 # Android Auto declaration and related XML
├── build.gradle.kts
└── proguard-rules.pro
```

## Requirements

- Android Studio
- Android SDK with the project target SDK installed
- JDK 21, as configured by the Gradle toolchain metadata
- A reachable CastCharm server for integration testing

The project uses the checked-in Gradle Wrapper. You do not need to install Gradle separately.

## Build and run

From the repository root:

```bash
./gradlew assembleDebug
```

Install the debug build on a connected device or emulator:

```bash
./gradlew installDebug
```

On Windows, use:

```bat
gradlew.bat assembleDebug
gradlew.bat installDebug
```

## Local development notes

The app expects a running CastCharm server. On first launch, enter the server URL and any configured credentials. The server URL is stored locally with DataStore, and API/session state is reused across app launches.

For emulator testing against a server running on the development machine, Android emulators cannot use `localhost` to reach the host computer. Use `10.0.2.2` instead. For testing against a real HTTPS server, use the public server URL.

Playback is handled by the Media3 service, not only by the foreground UI. When changing auth, stream URL generation, cookies, or server URL persistence, test both normal in-app playback and Android Auto/library playback paths.

## Architecture overview

### API layer

The API layer uses Retrofit and OkHttp to communicate with the CastCharm server. It handles login state, server URL configuration, and the endpoints needed for feeds, episodes, playback progress, downloads, and played-state updates.

### Local database

Room is used as the local cache and state store. The main persisted data types are:

- Feeds
- Episodes
- Download records
- Playback position and played state
- Local file paths for downloaded episodes

The local database is used for fast UI rendering, offline/downloaded playback, and background sync behavior.

### Playback

Playback is based on Media3 and ExoPlayer. `PlayerService` owns the ExoPlayer instance and exposes a MediaLibraryService for Android Auto and system media integrations. `PlayerController` connects the app UI to the media session.

Playback resolution should prefer a downloaded local file when one exists. If no local file exists and the app is online, playback streams from the CastCharm server. Offline mode should only play episodes that are already downloaded.

### Downloads

Downloads are handled with WorkManager. Download state is stored in Room, and downloaded files are saved to app-managed storage. Storage management should preserve the configured quota and remove older files according to the app's cleanup policy.

### Android Auto

Android Auto support is provided through Media3's media library/session APIs. The browse tree exposes sections such as recent/continue/downloaded content and podcast feeds. Test Android Auto changes with the Desktop Head Unit where possible, and test on a real device when changing playback behavior.

## Backend API endpoints used

The app talks to the same REST API the web interface uses (documented at `/api/docs` on any server). The Retrofit interface in `data/api/CastCharmApi.kt` is the authoritative list; grouped by area it currently covers:

| Area | Endpoints |
|---|---|
| Auth and keys | `GET /api/auth/status`, `POST /api/auth/login`, `POST /api/auth/logout`, `POST /api/auth/exchange-key`, `GET/PATCH /api/settings/api-keys…`, `DELETE /api/settings/api-keys/self` |
| Server info | `GET /api/status`, `GET /api/settings`, `GET /api/stats`, `GET /api/limits` |
| Feeds | `GET/POST /api/feeds`, `GET/PUT/DELETE /api/feeds/{id}`, `GET /api/feeds/{id}/episodes`, `GET /api/feeds/{id}/episode-index`, `GET /api/feeds/{id}/cover.jpg`, `POST /api/feeds/{id}/refresh`, `POST /api/feeds/refresh-all` |
| Episodes | `GET /api/episodes`, `GET /api/episodes/{id}`, `GET /api/episodes/{id}/stream`, `GET /api/episodes/{id}/cover.jpg`, `POST /api/episodes/{id}/progress`, `POST /api/episodes/{id}/download`, `POST /api/episodes/{id}/retry`, `POST /api/episodes/{id}/hide` and `/unhide`, `POST /api/episodes/bulk` (set played/unplayed), `GET /api/episodes/continue-listening`, `GET /api/episodes/suggestions` |
| Playlists | `GET/POST /api/playlists`, `PUT/DELETE /api/playlists/{id}`, `GET/POST /api/playlists/{id}/episodes`, `DELETE /api/playlists/{id}/episodes/{episode_id}`, `PUT /api/playlists/{id}/episodes/reorder`, `GET /api/playlists/episode-memberships`, `GET /api/playlists/feed-memberships` |
| Player queue | `GET/PUT /api/player/state`, `POST /api/player/play`, `POST /api/player/next`, `POST /api/player/prev` |

Played state is always *set* (via the bulk endpoint), never toggled, so a retried request can't flip it the wrong way. If server endpoint behavior changes, update the Retrofit interface, repositories, playback/download paths, and any related tests or manual QA notes together.

## Releases

Releases are signed in Android Studio (Build → Generate Signed Bundle / APK → APK → release) and published to GitHub with `release.sh`, which tags the current commit with the version from `app/build.gradle.kts`, attaches the APK as `castcharm-v<version>.apk`, and generates release notes from the commits since the previous tag. It needs the GitHub CLI (`gh`) and refuses to run with uncommitted changes, a stale APK, or an existing tag. Bump `versionCode` and `versionName` before every release.

## Contributing and license

Issues and pull requests are welcome. Please open an issue first for anything beyond a small fix so the approach can be agreed before the work. The code is released under the MIT License (see `LICENSE`).


## Main dependencies

- Jetpack Compose
- Navigation
- Lifecycle
- Room
- DataStore
- WorkManager
- Media3 / ExoPlayer / MediaSession
- Retrofit
- OkHttp
- Moshi
- Coil
- Kotlin coroutines
- Guava futures
- Reorderable (drag-to-reorder list support)
