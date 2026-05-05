# CastCharm Android

Native Android client for CastCharm. This repository is intended for developers who want to build, inspect, test, or contribute to the Android app. End-user installation and server setup documentation belong in the main CastCharm documentation.

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
- Auto-advance playback when queue context is active

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

The app currently depends on these CastCharm server endpoints:

| Endpoint | Purpose |
|---|---|
| `GET /api/auth/status` | Check current authentication state |
| `POST /api/auth/login` | Log in to the CastCharm server |
| `GET /api/feeds` | Fetch feed list |
| `GET /api/feeds/{id}/episodes` | Fetch episodes for a feed |
| `GET /api/episodes/{id}/stream` | Stream episode audio |
| `GET /api/episodes/{id}/file` | Download full episode file |
| `POST /api/episodes/{id}/progress` | Save playback position |
| `POST /api/episodes/{id}/played` | Toggle played state |
| `GET /api/episodes/continue-listening` | Fetch resumable episodes |
| `GET /api/feeds/{id}/cover.jpg` | Fetch feed artwork |
| `GET /api/playlists` | Fetch all playlists |
| `POST /api/playlists` | Create a new playlist |
| `PUT /api/playlists/{id}` | Update playlist metadata |
| `DELETE /api/playlists/{id}` | Delete a playlist |
| `GET /api/playlists/{id}/episodes` | Fetch episodes in a playlist |
| `POST /api/playlists/{id}/episodes` | Add episode to playlist |
| `DELETE /api/playlists/{id}/episodes/{episode_id}` | Remove episode from playlist |
| `PUT /api/playlists/{id}/episodes/reorder` | Reorder episodes in playlist |
| `GET /api/playlists/episode-memberships` | Get all playlists containing an episode |
| `GET /api/playlists/feed-memberships` | Get playlist memberships for episodes in a feed |
| `POST /api/player/play` | Start playback with context (feed or playlist) |
| `POST /api/player/next` | Skip to next episode in queue |
| `POST /api/player/prev` | Go to previous episode in queue |

If server endpoint behavior changes, update the Retrofit interface, repositories, playback/download paths, and any related tests or manual QA notes together.


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
