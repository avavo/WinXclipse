# Third-party notices

WinXclipse as a whole is licensed under the GNU General Public License v3.0 (see `LICENSE`).
It includes or depends on the following components, each under its own license.

## Source code adapted into WinXclipse

| Component | License | Where |
|---|---|---|
| [Winlator](https://github.com/brunodev85/winlator) (Copyright (c) 2023 BrunoSX) | MIT, see `LICENSES/MIT-Winlator.txt` | Most of the Java code base |
| [GameNative](https://github.com/utkarshdalal/GameNative) | GPL-3.0 | Steam connection and sign-in flow in `com.winlator.cmod.steam`; GOG sign-in, library and downloads in `com.winlator.cmod.gog` |
| [heroic-gogdl](https://github.com/Heroic-Games-Launcher/heroic-gogdl) | GPL-3.0 | GOG content system and dependency handling in `com.winlator.cmod.gog` |

Only GameNative's GPL-3.0 code is used. Its proprietary components (`libredirect.so`,
`libgamenative_drm.so`, `libsteambootstrap.so`) and its bundled `steam_api` DLLs are **not**
part of WinXclipse.

## Bundled programs

| Component | License | Where |
|---|---|---|
| [gbe_fork](https://github.com/Detanup01/gbe_fork) (Goldberg Steam Emulator fork), release-2026_09_27 | LGPL-3.0, see `app/src/main/assets/gbe/LICENSE.txt` | `app/src/main/assets/gbe` (unmodified Windows binaries: `steam_api(64).dll`, `steamclient(64).dll`, ColdClientLoader, GameOverlayRenderer, `steamclient_extra`) |

gbe_fork's source code for these binaries is available at
https://github.com/Detanup01/gbe_fork/tree/release-2026_09_27. WinXclipse copies the
libraries next to the user's Steam games at launch; they can be replaced with any other
build of gbe_fork.

## Downloaded when needed

Not part of the APK; fetched from their publishers the first time a game needs them.

| Component | License | Source |
|---|---|---|
| [Wine Mono](https://github.com/madewokherd/wine-mono) (Wine's .NET Framework) | MIT, with parts under other open-source licenses (see its release) | GitHub releases of madewokherd/wine-mono, or dl.winehq.org |
| GOG redistributables (Visual C++, DirectX, DOSBox, ScummVM…) | Their own licenses | GOG's dependency store, as GOG Galaxy installs them |

## Libraries

| Library | License |
|---|---|
| [JavaSteam](https://github.com/Longi94/JavaSteam) and its depot downloader | MIT |
| [Ktor](https://github.com/ktorio/ktor), [Okio](https://github.com/square/okio) | Apache 2.0 |
| [SpongyCastle](https://rtyley.github.io/spongycastle/) | MIT (Bouncy Castle licence) |
| [Bouncy Castle](https://www.bouncycastle.org/) | MIT (Bouncy Castle licence) |
| [ZXing](https://github.com/zxing/zxing) | Apache 2.0 |
| [Protocol Buffers](https://github.com/protocolbuffers/protobuf) | BSD 3-Clause |
| [OkHttp](https://github.com/square/okhttp), [Retrofit](https://github.com/square/retrofit) | Apache 2.0 |
| [Kotlin](https://github.com/JetBrains/kotlin), [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | Apache 2.0 |
| [Jetpack Compose and AndroidX](https://developer.android.com/jetpack/androidx) | Apache 2.0 |
| [Glide](https://github.com/bumptech/glide) | BSD, part MIT and Apache 2.0 |
| [Gson](https://github.com/google/gson) | Apache 2.0 |

## Fonts

| Font | License |
|---|---|
| [Exo 2](https://github.com/googlefonts/Exo-2.0) | SIL Open Font License 1.1, see `app/src/main/res/raw/ofl_exo2.txt` |
| [Barlow](https://github.com/jpt/barlow) | SIL Open Font License 1.1, see `app/src/main/res/raw/ofl_barlow.txt` |

## Steam

Steam and the Steam logo are trademarks of Valve Corporation. WinXclipse is not affiliated with
or endorsed by Valve. Game artwork shown in the Steam library is loaded from Valve's public CDN
and belongs to its respective owners.

## GOG

GOG and GOG.com are trademarks of CD PROJEKT S.A. WinXclipse is not affiliated with or endorsed by
GOG. Game artwork shown in the GOG library is loaded from GOG's public servers and belongs to its
respective owners.
