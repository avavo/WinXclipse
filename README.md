# WinXclipse

WinXclipse is an experimental Android project based on Winlator CMOD and adapted for Samsung Exynos devices with Xclipse GPUs.

## Goal

The project provides an Exynos/Xclipse-oriented environment for running Windows applications and games on Android. Development is focused on graphics wrappers, Wine and Proton runtimes, FEXCore, container management, input controls, diagnostics, external content delivery, and device-specific compatibility improvements.

## Disclaimer

WinXclipse is experimental. Performance, stability, graphics-driver behavior, and game compatibility can vary according to the device, runtime, wrapper, driver, and container configuration.

Use it at your own risk and test each configuration individually.

## Current release: WinXclipse v0.8.6

Version 0.8.6 builds on the large runtime and interface foundation introduced in v0.8.5. This release focuses on external content management, Xclipse driver support, safer defaults, BCN texture handling, the in-session sidebar, diagnostics, and HUD accuracy.

### What changed since v0.8.5

#### Downloads and external packages

- Renamed the **Contents** area to **Downloads**.
- Added dynamically refreshed Xclipse driver downloads from the [ExynosTools releases](https://github.com/WearyConcern1165/ExynosTools/releases) and [MdiEx releases](https://github.com/avavo/MdiEx/releases).
- Refactored the inherited driver-package infrastructure into an Xclipse-oriented installer without removing custom-driver installation support.
- Improved displayed package names by preserving the complete runtime or driver filename while hiding packaging suffixes such as `.wcp`, `.xz`, and `.tzst`.
- Added custom wrapper installation from a local file or an arbitrary HTTP/HTTPS URL.
- Custom wrappers can be supplied as `.tzst`, `.tstz`, `.tzts`, `.zst`, or a direct `.so` library.
- The user chooses the wrapper name during installation; installed entries use the `Wrapper-Name` format and become available to containers.
- Remote catalogs are cached, allowing the last successfully retrieved list to remain available if a refresh fails.

#### Runtime and default cleanup

- Updated the bundled Box64 package to `0.4.3-260519-024717c` and removed the older bundled Box64 packages.
- Set FEXCore 2608 as the default and removed the bundled FEXCore 2505 and 2507 packages.
- Added DXVK `1.11.1-sarek`.
- Removed DXVK `2.2.116` and the bundled GPLAsync variants.
- New containers and shortcuts start with the **Stability** FEXCore preset.
- VKD3D now starts as **None**, while remaining selectable from the combined DXVK + VKD3D configuration.
- **Show FPS** is enabled by default.
- ASTC and ETC2 transcoding start disabled, so texture transcoding is opt-in.

#### Xclipse GPU and BCN handling

- Added real GPU renderer detection with recognition for the Xclipse family, including Xclipse 920, 940, and 950.
- Replaced Qualcomm-specific wording in the visible driver workflow with Xclipse-oriented naming while preserving the underlying package-management compatibility.
- Replaced the BCN runtime layer with the custom Leegao BCN layer.
- Ported the complete BCN activation flow used by the reference implementation, including compute mode, automatic mode, software/full emulation, cache control, ASTC transcoding, ETC2 transcoding, and quality presets.
- The BCN environment is enabled only after both the layer library and Vulkan manifest are verified inside the runtime filesystem.
- Failed or incomplete BCN extraction now disables the layer instead of exposing configuration options that do not affect the runtime.

#### In-session sidebar and controls

- Reworked the sidebar displayed while a container is running.
- Added a cleaner time and battery header, pause/resume, help, terminal, and power-style exit actions.
- Grouped detailed input options behind the Input menu.
- Added a dedicated **Controller** action: tapping it toggles touchscreen controls, while its settings action opens controller configuration.
- Added **Virtual Gamepad 2**, based on the reference virtual-gamepad profile, while preserving user-selectable default profiles.
- Added direct HUD enable/disable behavior with a separate HUD configuration action.
- Added an in-session FPS limiter with Unlimited, 30, 45, 60, 90, and 120 FPS options. Limiting is applied at the guest presentation stage instead of blocking the Android rendering thread.
- Enlarged shortcut touch targets to match their visible action icons.

#### Task Manager and diagnostics

- Added a lightweight expandable Task Manager directly to the in-session sidebar.
- Reports total CPU use and per-core current/maximum frequencies.
- Reports RAM percentage, used memory, and total available memory.
- Displays processes with PID and memory use.
- Adds process actions for bringing a window to the front, terminating a process, and selecting CPU affinity.
- Refreshes statistics periodically without requiring a full-screen diagnostics window.

#### HUD corrections

- Corrected HUD renderer/API detection so DXVK, VKD3D, WineD3D, Vulkan, and OpenGL are reported from the active runtime instead of a forced fallback value.
- Corrected wrapper reporting so Kirimu, GameNative, Leegao, LD24, and other wrapper families are identified separately from the graphics API.
- Added support for both runtime renderer properties used by the graphics stacks.
- Prevented an unrelated destroyed window from clearing the current HUD renderer state.
- Improved battery power reporting using real charge-counter and voltage data when exposed by the device.

#### Experimental performance option

- Added an opt-in **Experimental Performance** checkbox to containers and shortcuts.
- The option is disabled by default and applies a small set of compatibility/performance environment flags only when selected.
- Existing rendering behavior remains the default, allowing the experimental profile to be tested per container without permanently changing the application renderer.

#### Packaging and update safety

- Updated the application version to `0.8.6` (`versionCode 22`).
- Generated three separately installable identities:
  - Main: `com.win.xclipse`
  - AnTuTu: `com.antutu.ABenchMark`
  - Geekbench 6: `com.primatelabs.geekbench6`
- File providers and other authorities use the active application ID, preventing controls and file access from remaining tied to another package name.
- Added repository exceptions for the new runtime, driver, and control-profile assets so future source commits and clones retain the files required to reproduce the APK.

## Features introduced in v0.8.5

Version 0.8.5 was the major foundation for the current WinXclipse line. It introduced:

- Dynamic WinXclipse release catalogs for Wine/Proton, FEXCore, Box64, DXVK, and VKD3D packages.
- Installation support for `.wcp`, `.wcp.xz`, and compatible compressed content packages.
- Proton 10/11 and ARM64EC runtime handling improvements.
- FEXCore preset creation, rename, duplicate, delete, import, and export actions.
- FEXCore preset selection in Settings, Containers, and Shortcuts.
- Combined DXVK and VKD3D configuration.
- Advanced wrapper configuration, Vulkan information, Present Modes, memory controls, and texture-transcoding options.
- Winlator HUD and MangoHud selection.
- Download and installation progress with numerical percentages.
- USB-C/file-provider fixes and file-manager performance improvements.
- Reorganized application and container navigation.
- Automatic update checking through GitHub releases.
- AMOLED dark mode, ice-white light mode, purple accents, updated branding, and extensive dialog/control theme corrections.

## Bundled wrapper family

The built-in graphics-driver list includes:

- Wrapper
- Wrapper-v2
- Wrapper-Leegao
- Wrapper-EV1
- Wrapper-EV2
- Wrapper-Kirimu
- Wrapper-LD24
- Wrapper-Ref4ik-v6
- Wrapper-GameNative

Additional wrappers installed from **Downloads** appear alongside the built-in entries. Availability and compatibility depend on the device and selected container configuration.

## Downloads and runtime sources

WinXclipse can discover supported runtime packages from the configured WinXclipse GitHub releases without requiring a new APK for every newly uploaded asset. Xclipse driver lists are retrieved separately from the ExynosTools and MdiEx releases.

Only install packages intended for your architecture, GPU, and runtime type. An entry appearing in Downloads does not guarantee that it is compatible with every device or game.

## Previous releases

### WinXclipse v0.7.6

Version 0.7.6 established the first organized source-based WinXclipse build. It moved earlier APK-only modifications into the source tree and introduced the project identity, Xclipse GPU Drivers area, wrapper organization, backend-log fixes, driver-selector cleanup, and Exynos/Xclipse-focused metadata.

### WinXclipse v0.7.5

Version 0.7.5 introduced the initial WinXclipse visual identity and expanded the original wrapper selection through APK-level modifications.

## Credits

WinXclipse builds upon the work of:

- [Winlator](https://github.com/brunodev85/winlator) by Brunodev85
- Winlator CMOD by coffincolors and Pipetto-crypto
- Winlator Glibc by longjunyu2
- Winlator OpenXR by lvonasek
- [Winlator Mali](https://github.com/GunaCharanTeja/WinlatorMali) for ports and implementation references
- [ExynosTools](https://github.com/WearyConcern1165/ExynosTools) for Exynos/Xclipse driver resources
- [MdiEx](https://github.com/avavo/MdiEx) for Xclipse driver resources
- WinXclipse Exynos/Xclipse adaptation by Álvaro
