# Third-Party Notices

PixelPlayerOSS is licensed under the GNU General Public License v3.0 (GPL-3.0). The app also includes third-party libraries and font assets. This file records source and license evidence useful for app-store and F-Droid review.

## Bundled Font Assets

| Asset | Upstream | License |
| --- | --- | --- |
| `app/src/main/res/font/gflex_variable.ttf` | Google Sans Flex, `https://fonts.google.com/specimen/Google+Sans+Flex/license?preview.script=Latn` | SIL Open Font License 1.1 |
| `app/src/main/res/font/genre_variable.ttf` | Roboto Flex, `https://github.com/googlefonts/roboto-flex` | SIL Open Font License 1.1 |

The OFL text is included in `app/src/main/assets/licenses/texts/OFL-1.1.txt`, next to the full text of every other license the app shows under Licenses & attribution.

## Bundled Smart Mix Data Assets

| Asset | Source | License |
| --- | --- | --- |
| `app/src/main/assets/nlp/artist-genres.txt` | Artist-to-genre labels derived from Wikidata, `https://www.wikidata.org` | CC0 1.0 (public domain dedication) |
| `app/src/main/assets/nlp/concepts.txt` | Word-to-genre-family/mood concept table, generated offline with a locally run language model and shipped as static, reviewable text | Distributed under the project license (GPL-3.0) |

Both files are read only on the device by the offline Smart Mix engine. The app makes no network request and runs no AI model to use them.

## Native/Binary Runtime Artifacts

| Artifact | APK native library | Upstream/source | License evidence |
| --- | --- | --- | --- |
| `org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1` | `libffmpegJNI.so` | `https://github.com/jellyfin/jellyfin-androidx-media` | Maven POM declares GPL-3.0 |
| `io.github.kyant0:taglib:1.0.6` | `libtaglib.so` | `https://github.com/Kyant0/taglib` | Maven POM declares Apache-2.0 |
| `androidx.graphics:graphics-shapes:1.1.0` | `libandroidx.graphics.path.so` | `https://android.googlesource.com/platform/frameworks/support` | Google Maven POM declares Apache-2.0 |

The Jellyfin FFmpeg decoder bundled in the APK is GPL-3.0, consistent with the PixelPlayerOSS source license (GPL-3.0).

## JitPack Artifacts

| Artifact | Why present | Upstream/source | License evidence |
| --- | --- | --- | --- |
| `com.github.racra:smooth-corner-rect-android-compose:v1.0.0` | Direct Compose UI dependency | `https://github.com/racra/smooth-corner-rect-android-compose` | POM/upstream declare MIT |
| `com.github.philburk:jsyn:3f6b44b853bccc0d2e3027104d575fcc5ccb6d4e` | Transitive dependency of `androidx.media3:media3-exoplayer-midi` | `https://github.com/philburk/jsyn` | POM declares Apache-2.0 |

## Main Library License Families

| Family | Examples | Typical license/source |
| --- | --- | --- |
| AndroidX and Jetpack Compose | Activity, AppCompat, Benchmark, Compose, Glance, Hilt extensions, Lifecycle, Media3, Navigation, Paging, Room, Security, WorkManager | Apache-2.0, AOSP/AndroidX source |
| Google libraries | Material Components, Gson, Guava transitive pieces, KSP/Gradle plugin ecosystem pieces where applicable | Apache-2.0 or compatible Google-published licenses |
| JetBrains/Kotlin libraries | Kotlin stdlib, coroutines, serialization, immutable collections | Apache-2.0 |
| Square libraries | OkHttp, Retrofit | Apache-2.0 |
| Ktor/Netty | Ktor server CIO/core, Netty transitive libraries | Apache-2.0 |
| Audio/metadata libraries | JAudioTagger, Vorbis Java | LGPL/MPL/BSD-family upstream licenses; verify current POMs when versions change |
| UI utilities | Accompanist, Coil, Capturable, CodeView, Reorderable, Wavy Slider | Apache-2.0/MIT or compatible licenses; verify current POMs when versions change |
| Text/search utilities | Kuromoji, pinyin4j | Apache-2.0/BSD-style licenses; verify current POMs when versions change |
| Security/compatibility transitive constraints | Bouncy Castle, Commons Lang, HttpClient, JDOM2, jose4j | MIT/Apache-2.0/Bouncy Castle/JDOM-style licenses; verify current POMs when versions change |

Run a dependency/license review again whenever `gradle/libs.versions.toml` changes.

The current review notes are tracked in `docs/DEPENDENCY_LICENSES.md`.


## Telegram integration

| Artifact | Upstream | License |
| --- | --- | --- |
| `com.github.tdlibx:td:1.8.56` Java/Android wrapper | `https://github.com/tdlibx/td` | Apache License 2.0 |
| Telegram Database Library (TDLib) native/runtime components | `https://github.com/tdlib/td` | Boost Software License 1.0 |

The TDLib Java/Android distribution and its upstream native library are separate components with their own notices. The wrapper repository's Apache-2.0 license and upstream TDLib's Boost Software License 1.0 are retained; see each upstream repository for the complete license text.
