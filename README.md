
<p align="center">
  <img src="assets/PixelPlayerOSS.svg" alt="App Icon" height="250"/>
</p>

<p align="center">
  <a href="https://github.com/sahinmehemood/New-pixelplay/actions/workflows/android-ci.yml">
    <img src="https://img.shields.io/github/actions/workflow/status/sahinmehemood/New-pixelplay/android-ci.yml?branch=feat/telegram-cloud-ai&style=for-the-badge&label=Android%20CI" alt="Android CI">
  </a>
  <a href="https://github.com/sahinmehemood/New-pixelplay">
    <img src="https://img.shields.io/badge/project-New%20PixelPlay-2AABEE?style=for-the-badge&logo=github" alt="New PixelPlay project">
  </a>
  <img src="https://img.shields.io/badge/Android-11%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android 11+">
  <img src="https://img.shields.io/badge/License-GPLv3-blue?style=for-the-badge" alt="GPLv3 license">
</p>

<p align="center">
  <img src="assets/screenshot1.jpeg" alt="PixelPlayerOSS home screen" width="205"/>
  <img src="assets/screenshot2.jpeg" alt="PixelPlayerOSS now playing screen" width="205"/>
  <img src="assets/screenshot3.jpeg" alt="PixelPlayerOSS library screen" width="205"/>
  <img src="assets/screenshot4.jpeg" alt="PixelPlayerOSS lyrics screen" width="205"/>
</p>

## What This Repository Is

This is the Android source submodule used by [New PixelPlay](https://github.com/sahinmehemood/New-pixelplay), a Telegram-enabled fork built on the GPL-licensed PixelPlayerOSS codebase.

The Android application keeps the existing local music library, Media3 playback engine, Jetpack Compose UI, and offline-first behavior. This fork adds Telegram as its **only cloud account and cloud music source**. Navidrome/Subsonic and Jellyfin are not exposed as account, navigation, or playback providers in this product.

Package name: `com.lostf1sh.pixelplayeross`

The parent repository owns the product README, submodule pin, CI workflow, and release process. This repository contains the Android application source.

## Features

| Area | Highlights |
| --- | --- |
| Playback | Media3 playback engine, FFmpeg support, gapless playback, crossfade, custom transitions, queue controls, shuffle, repeat, sleep timer, external file playback |
| Library | Local scanning for MP3, FLAC, AAC, OGG, WAV, M4A, albums, artists, genres, folders, favorites, playlists, stats, metadata editing |
| Telegram | User-account sign-in, public/private channel discovery, audio indexing, and progressive playback with byte-range seeking |
| Lyrics | Embedded lyrics, local `.lrc` files, lyrics import/editing, optional LRCLIB lookup |
| Artwork | Local artwork, album-art palette extraction, optional Deezer artist image lookup |
| Metadata | On-demand MusicBrainz matching for recording, release, and artist identifiers |
| UI | Jetpack Compose, Material 3, dynamic color, light/dark themes, Glance widgets, animated player surfaces |
| Backup | Preferences, playlists, favorites, lyrics, stats, and app state backup/restore |

## Online Services

PixelPlayerOSS separates offline playback from network lookups.

| Service | Purpose | Default |
| --- | --- | --- |
| MusicBrainz | On-demand metadata matching and identifier enrichment | Only when requested |
| LRCLIB | Search online lyrics when local or embedded lyrics are missing | Off |
| Deezer | Fetch missing artist artwork and cache it locally | Off |

LRCLIB and Deezer can be enabled during first-run setup or later from `Settings > Music Management > Optional online services`. MusicBrainz searches run only when you choose the lookup action for a track.

## Requirements

| Requirement | Version |
| --- | --- |
| Android | 11 or newer, API 30+ |
| JDK | 21 |
| Android SDK | compile/target 37 |

## Build From Source

Clone the product repository with its pinned Android source submodule:

```sh
git clone --branch feat/telegram-cloud-ai --recurse-submodules https://github.com/sahinmehemood/New-pixelplay.git
cd New-pixelplay/pixelplayer-base
```

Create your own Telegram application at [my.telegram.org](https://my.telegram.org) if you plan to use Telegram. The app asks for the API ID and API hash on first use and stores them in Android Keystore-backed encrypted preferences. They are not embedded in the published APK and do not need to be GitHub Actions secrets. For local development only, optional build-time values can be placed in the uncommitted `local.properties`.

Build and test with JDK 21 and an installed Android SDK:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

To build one universal debug APK for local debugging:

```sh
./gradlew :app:assembleDebug -Ppixelplayer.enableAbiSplits=false
```

The product CI builds separate `arm64-v8a` and `armeabi-v7a` debug APKs. It verifies TDLib's matching native library, ABI contents, APK signatures, and unit tests before publishing a versioned prerelease. The published debug APKs are test builds, not production-signed releases.

## Downloads and Releases

The product's installable APKs are published by [New PixelPlay](https://github.com/sahinmehemood/New-pixelplay/releases). This source submodule does not publish a second, independent Android application release stream.

Each CI prerelease contains separate `arm64-v8a` and `armeabi-v7a` debug APKs plus `SHA256SUMS.txt`. These are debug-signed testing builds. They are not interchangeable with upstream PixelPlayerOSS or F-Droid-signed installations without uninstalling the existing package first.

## Support

If PixelPlayerOSS is useful to you, you can support ongoing development through [GitHub Sponsors](https://github.com/sponsors/lostf1sh).

## Project Structure

```text
New-pixelplay/
├── .github/workflows/      # Product CI and APK release automation
├── pixelplayer-base/       # This pinned Android source submodule
├── .gitmodules
└── README.md

pixelplayer-base/
├── app/src/main/java/com/lostf1sh/pixelplayeross/
│   ├── data/               # Room, Telegram, repositories, services, workers
│   ├── di/                 # Hilt modules and qualifiers
│   ├── presentation/       # Compose screens, components, navigation, ViewModels
│   ├── ui/                 # Theme and Glance widgets
│   └── utils/              # Shared utilities
└── baselineprofile/        # Macrobenchmark and baseline profile generation
```

Keep product-level documentation and CI configuration in the parent repository. Keep Android source and source-level tests in this submodule.

## Tech Stack

| Area | Technology |
| --- | --- |
| Language | Kotlin |
| UI | Jetpack Compose |
| Design | Material 3 |
| Playback | AndroidX Media3, ExoPlayer, FFmpeg |
| Database | Room |
| Dependency Injection | Hilt |
| Preferences | DataStore |
| Background Work | WorkManager |
| Networking | Retrofit, OkHttp |
| Images | Coil |
| Metadata | TagLib |

## Contributing

Contributions are welcome. Open an issue or pull request with a focused change and include test/build results when possible.

Useful local checks:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:compileDebugKotlin
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:lintDebug
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:testDebugUnitTest
```

Release process: [docs/RELEASE.md](docs/RELEASE.md)

F-Droid notes: [docs/FDROID.md](docs/FDROID.md)

Privacy policy: [PRIVACY.md](PRIVACY.md)

Security policy: [SECURITY.md](SECURITY.md)

## License

PixelPlayerOSS is licensed under the [GNU General Public License v3.0](LICENSE) (`SPDX-License-Identifier: GPL-3.0-or-later`).

```
PixelPlayerOSS
Copyright (C) 2026 Theo Vilardo

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
```

Distributed APKs include third-party components under their own licenses. In particular, the optional FFmpeg decoder dependency `org.jellyfin.media3:media3-ffmpeg-decoder` is GPL-3.0; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

<p align="center">
  Maintained by <a href="https://github.com/lostf1sh">lostf1sh</a>
</p>
<p align="center">
  Logo designed by <a href="https://github.com/NPSummers">Aureal</a>.
</p>


## Telegram music and optional AI personalization

This fork exposes Telegram as its only cloud account and music-streaming provider. It uses TDLib's on-device database/session, QR-device linking, phone-number verification, optional two-step verification, public username lookup, private invite-link joining, joined-chat browsing, audio-message and document indexing, and a token-protected loopback HTTP stream proxy with byte-range support for seeking. Telegram tracks are passed to the existing Media3 playback queue as temporary items; they are not inserted into the local Room music library. Navidrome/Subsonic and Jellyfin account and dashboard routes are intentionally not part of this product's user-facing experience.

### Telegram API credentials

Create your own Telegram application at [my.telegram.org](https://my.telegram.org). On first use, open **Home → Cloud library → Telegram** or **Settings → Accounts → Telegram music** and enter the API ID and API hash. The app validates them and stores them in Android Keystore-backed encrypted preferences. The QR login link is short-lived, is never logged or persisted, and is hidden from screenshots while displayed.

For local development only, optional build-time values can be placed in the uncommitted `local.properties` file:

```properties
TELEGRAM_API_ID=your_api_id
TELEGRAM_API_HASH=your_api_hash
```

The TDLib account database is stored in app-private storage and excluded from Android cloud backup and device transfer. The stream proxy binds to loopback only, requires a random per-process token, and invalidates active URLs when the account signs out or its API credentials are reset.

### AI personalization

AI personalization is disabled until configured from **Settings → Accounts → AI personalization**. It accepts an OpenAI-compatible chat-completions endpoint, model name, and your own API key. The key is stored using Android Keystore-backed encrypted preferences. When enabled, only local-library metadata and coarse listening signals are sent to rank tracks; audio files, file paths, content URIs, Telegram credentials, and Telegram session data are not sent.

AI personalization currently ranks the local music library for Smart Mix. It does not process Telegram audio or Telegram channel content. AI output is treated as untrusted: only IDs from the submitted local candidate list are accepted, and no new tracks are downloaded or added to the library.
