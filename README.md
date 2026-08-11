# WinXclipse

WinXclipse is an experimental Android project based on Winlator CMOD, adapted for Samsung Exynos devices with Xclipse GPUs.

## Goal

The project provides a cleaner, compatibility-oriented environment for running Windows software on Exynos/Xclipse devices. It focuses on graphics wrappers, containers, FEXCore, Proton/Wine runtimes, input controls, diagnostics, and practical device-specific adjustments.

## Disclaimer

WinXclipse is experimental. Performance, stability, game compatibility, and driver behavior vary by device, runtime, wrapper, and game.

Use it at your own risk and test each configuration individually.

## Current release: WinXclipse v0.8.5

Version 0.8.5 is the first major feature release after the source-based 0.7.6 line. It expands the project from a visual and driver-focused adaptation into a more complete runtime, content, and container-management build.

### What changed since v0.7.6

- Renamed the main application package to `com.win.xclipse`, with dynamic internal paths to preserve controls, providers, and container data.
- Added build variants for AnTuTu Benchmark (`com.antutu.ABenchMark`) and Geekbench 6 (`com.primatelabs.geekbench6`).
- Added remote, release-based Contents catalogs. New files published in the configured GitHub releases can be discovered without rebuilding the APK.
- Added installation support for Proton packages distributed as `.wcp`, `.wcp.xz`, and compatible compressed formats.
- Added Proton 10/11 handling improvements and faster first-boot runtime setup.
- Added the GameNative graphics wrapper alongside the existing Xclipse-focused wrappers.
- Expanded container graphics-driver configuration with Vulkan version, GPU information, Present Modes, memory resource type, BCN emulation/cache, texture transcoding, ASTC/ETC2 options, and related compatibility settings.
- Combined DXVK and VKD3D configuration into one container dialog, including a `None` choice for VKD3D.
- Added FEXCore preset management: Performance, Stability, Intermediate, and Compatibility presets, plus create, rename, duplicate, delete, import, and export actions.
- Added FEXCore preset selection to Settings, Containers, and Shortcuts.
- Added selectable FPS HUD support: Winlator HUD, MangoHud, or disabled. The Winlator HUD reports wrapper/API information and runtime statistics.
- Improved Content downloads and installation feedback with numerical percentage progress.
- Added a Winlator Mali-inspired file manager, improved USB-C file access, and reduced slowdowns when browsing large or deep folders.
- Reorganized navigation: input controls are grouped with Controller Manager; Saves, Box64 RCFile, and Backend Logs are available from Settings; container-side keyboard and mouse settings are grouped under Input.
- Added an updated Task Manager interface with process controls, CPU information, per-core clock reporting, and RAM usage.
- Added automatic update checking with an in-app update prompt.
- Refreshed the application theme and visual identity with AMOLED dark mode, ice-white light mode, purple accents, updated side-menu branding, and corrected themed dialogs, buttons, controls, and loading indicators.
- Removed unsupported or unwanted bundled download choices, including Snapdragon `v762`, `v805`, Turnip entries, Proton 9 x86, DXVK 1.7.1, Sarek, and Stripped.

### Wrappers

The graphics-driver list includes the Xclipse-oriented wrapper family and additional compatible wrappers, including:

- Wrapper
- Wrapper-v2
- Wrapper-Leegao
- Wrapper-EV1
- Wrapper-EV2
- Wrapper-Kirimu
- Wrapper-Ludashi-2-4
- Wrapper-Ref4ik-v6
- Wrapper-GameNative

Availability can vary according to installed Contents and the device.

### Contents and runtimes

Contents are retrieved from the project releases and are managed separately from the APK when possible. This keeps the application updateable without bundling every runtime in each build.

Available categories include Wine/Proton runtimes, FEXCore, Box64, DXVK/VKD3D, and graphics drivers. Only install packages compatible with your device and intended container configuration.

## WinXclipse v0.7.6

Version 0.7.6 established the first organized, source-based WinXclipse build. It moved earlier APK-only modifications into the source tree and introduced the project identity, Xclipse GPU Drivers area, wrapper organization, backend-log fixes, driver-selector cleanup, and Exynos/Xclipse-focused metadata and documentation.

## Credits

WinXclipse builds upon the work of:

- [Winlator](https://github.com/brunodev85/winlator) by Brunodev85
- Winlator CMOD by coffincolors and Pipetto-crypto
- Winlator Glibc by longjunyu2
- Winlator OpenXR by lvonasek
- [Winlator Mali](https://github.com/GunaCharanTeja/WinlatorMali) for ports and implementation references
- WinXclipse Exynos/Xclipse adaptation by Álvaro
