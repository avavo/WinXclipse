# WinXclipse

WinXclipse is an experimental Android project based on Winlator CMOD and adapted for Samsung Exynos devices with Xclipse GPUs.

## Goal

The project provides an Exynos/Xclipse-oriented environment for running Windows applications and games on Android. Development is focused on graphics wrappers, Wine and Proton runtimes, FEXCore, container management, input controls, diagnostics, external content delivery, and device-specific compatibility improvements.

## Disclaimer

WinXclipse is experimental. Performance, stability, graphics-driver behavior, and game compatibility can vary according to the device, runtime, wrapper, driver, and container configuration.

Use it at your own risk and test each configuration individually.

## Current release: WinXclipse v0.9

Version 0.9 focuses on the video and audio configuration tabs, shortcut artwork, controller handling, services and CPU management, graphics configuration cleanup, HUD alerts, and a new touchscreen control style.

### What changed since v0.8.6

#### Video tab

- Ported the video configuration tab from [Winlator Ludashi](https://github.com/StevenMXZ/Winlator-Ludashi) into containers and shortcuts.
- GPU name and present mode selection moved into the new video tab; added texture filtering and a red/blue channel swap option.
- Renderer selection (Vulkan, OpenGL, GDI) lives in the video tab as the primary graphics backend switch.
- Fake HDR from Winlator Mali is available through the in-session sidebar display options.

#### Audio

- Default audio driver changed to **PulseAudio**, still selectable per container or shortcut.
- New audio driver configuration dialog with a volume option; the MIDI soundfont setting is managed inside it.

#### Shortcut artwork

- Three configurable sources: **Browser** (default on first boot), **EXE icon**, and **Custom** — searchable online through SteamGridDB with EXE-icon fallback, settable globally or per shortcut.

#### Controls and visuals

- New dark glassy touchscreen control style: gradient button bodies with thin light borders and bold labels, four-petal D-pad with outward chevron arrows, tick-marked analog sticks, and a matching trackpad.
- Restored the light purple border around containers in the containers list.

#### Input, services and CPU

- Exclusive XInput mode; controller-fix files applied automatically with retry on partial failure.
- Aggressive startup selection protects winebus/winehid/MountMgr/PlugPlay while disabling non-critical Wine services; corrected the Wine `Ndis` registry key.
- Separate WoW64 CPU list is back for pinning 32-bit processes to different cores.

#### Graphics configuration

- Max Device Memory exists only in the Graphics Driver configuration; DDraw wrapper moved inside the combined DXVK + VKD3D config (default WineD3D).
- Default DXVK changed to `async-1.10.3`; BCN layer files from v0.8.x are cleaned up on update.

#### Fixes and performance

- Fixed repeated online artwork retries while scrolling and a recycled-bitmap risk between artwork download and reload.
- Hardened shortcut input parsing against corrupted stored values.
- Unified dark-mode detection across all remaining dialogs and screens (follow-system-theme aware).
- Control overlay no longer allocates native shaders per frame; rumble/turbo poller idles at ~60 Hz instead of ~200 Hz; playtime saves every 30 s instead of every 1 s; cover art decodes downsampled; controller motion-event logging removed; mouse-move timer restarts after re-attach.
- Stylus hover logging removed from the touchpad hot path.
- Fixed the inverted exclusive XInput behavior: exclusive mode now locks XInput-only (DInput forced off), while non-exclusive mode lets both be toggled freely with the simultaneous-use warning.
- Controller-fix retry now verifies all three files (`dinput.dll` included) before marking installation complete.
- Playtime autosave no longer double-schedules after `onResume`, restoring the real 30 s cadence.
- BCn emulation controls in the Graphics Driver dialog are now applied at launch and match the Winlator-Mali reference mapping: none/partial/full/auto → `WRAPPER_EMULATE_BCN` 0/1/2/3, compute layer enabled only for full/auto (with `BCN_COMPUTE_AUTO`=0/1), software type force-disables it via the manifest's `DISABLE_BCN_COMPUTE` key, cache passes through, and ASTC/ETC2 transcode remain mutually exclusive. Wrappers with native BCN handling (GameNative, Kirimu, Ref4ik-v6) skip the shared Leegao layer. The section stays visible only when Experimental BCN is enabled.
- New CPU cluster detection reads `cpufreq` data so the WoW64 fallback list pins 32-bit processes to performance cores on Exynos DynamIQ SoCs (Exynos 2200/2400 style 4+3+1 layouts), keeping LITTLE cores free for Android audio/input threads; homogeneous devices and explicit per-container lists are unaffected.
- GPU renderer detection results are cached instead of re-running JNI/regex checks per call, and the per-frame `glGetError` validation became debug-only to avoid implicit command-buffer syncs on tile-based Xclipse RDNA2 GPUs.
- Experimental Performance additionally sets `vblank_mode=0` so OpenGL/zink titles skip vsync waits (opt-in only).

#### Interface and usability

- Added wrapper credits to the About dialog and release notes: Wrappers from [Leegao](https://github.com/leegao/bionic-vulkan-wrapper), [GameNative](https://github.com/utkarshdalal/GameNative), [Ref4ik-v6](https://github.com/REF4IK/CronyX-), and Kirimu.
- Removed the bundled DXVK 1.10.1 and 1.10.3 entries, and the Wrapper-EV1, Wrapper-EV2, and Wrapper-LD24 wrappers.
- GPU Name now falls back to "Device" whenever no valid name is configured.
- Containers list has more vertical spacing between entries.
- The Audio Driver row is visible again in shortcut settings, and "Current Version" now shows the selected wrapper too (e.g. "Wrapper-Kirimu · System").
- Processor affinity grids wrap into centered rows of 4-5 cores with clearance above the confirm button, so every core (CPU8/CPU9 on decacore Exynos) is reachable in both containers and shortcuts.
- New HUD setting "Disable RAM warning" (with a ? help) hides the blinking RAM alert and its high-memory dialog; the choice is persisted per session preferences.
- New ? help next to the Graphics Driver selector explains that Kirimu and GameNative are recommended for BCn texture errors in DirectX 11/12 games.
- New ? help next to ASTC Transcode explains the Leegao BCN layer trade-offs in our own dialog style.
- All ? help popups now measure their wrapped text correctly — long explanations are no longer clipped to a single line.
- With Wrapper-Kirimu selected and Experimental BCN enabled, BCn options lock to Kirimu's profile: emulation type fixed to software, ASTC/ETC2 transcodes disabled, and BCn cache defaulting to 1.

#### Follow-up fixes

- The Video tab renderer choice (Vulkan/OpenGL/GDI) now persists: session startup no longer forces the Wine registry key back to GL on every run.
- GPU Name starts from "Device" reliably — the Wine-tab GPU spoof spinner no longer leaks its selection into the Video tab.
- Shortcut settings show the driver version selector on its own full-width row, and "Current Version" displays both wrapper and driver (e.g. "Wrapper-Kirimu · System"); the container editor keeps the original side-by-side rows.
- ? help popups use a themed dialog background with matching text color on both dark and light themes.
- New containers default the DDraw wrapper to **None** (Wine built-in ddraw instead of a proxy DLL).
- Experimental BCN defaults the BCn Emulation Cache to 1 when nothing was configured.
- Startup Selection fixed: Essential keeps essential+protected services and Aggressive keeps only the protected ones — previously both behaved identically due to an inverted condition.
- Bundled `vkd3d-proton-3.0.1b.wcp` installs automatically and appears in the DXVK+VKD3D selector; VKD3D extraction also matches the "-0" suffixed bundled archives (`vkd3d-2.8-0.tzst` etc.) that previously failed silently.
- Bundled `Dxvk-2.6.2-arm64ec-gplasync.wcp` installs automatically and appears in the DXVK+VKD3D selector as `2.6.2-arm64ec-gplasync` with async and GPLAsync cache toggles enabled for it.
- Per-SoC RAM tiers (8 GB for 1480/1580/1680/2200/2400e-class devices, 12 GB for 2600) are known to the detector and back up the unified-memory VRAM cap when the kernel report is unavailable.
- GPU detection recognizes the whole Xclipse family (530, 540, 550, 920, 940 including the Exynos 2400e variant, 950, 960) with its AMD RDNA generation (RDNA2: 530/540/920, RDNA3: 550/940/950, RDNA4: 960), and maps each GPU to its Exynos SoC; the FPS HUD shows the detected pairing, e.g. "Xclipse 940 RDNA3 (Exynos 2400/2400e)".
- On Xclipse 530/540 (low-tier RDNA2, one or two WGPs), Experimental BCN defaults to BCn-to-ASTC transcoding when no transcode option was chosen explicitly — these GPUs decode ASTC in hardware, while per-frame compute emulation competes directly with game rendering.
- Frequency-based cluster detection covers octa-core (Exynos 1480/1580) and deca-core (Exynos 2400/2400e/2500/2600) layouts alike, so performance-core defaults for 32-bit guest processes are correct on every supported SoC.

- Fixed ARM64EC guests dying silently before creating any window when running the bundled Proton arm64ec under FEXCore: the launcher requested the legacy `libarm64ecfex.dll` bridge instead of `libwow64fex.dll`, so every 64-bit game on this path exited at startup (black screen, only Wine services left). The correct bridge is now requested automatically per package.

#### Native and memory optimizations (unified LPDDR awareness)

- Native X11 blits (`copyAreaOp`, `fillRect`, `drawLine`) now process whole 32-bit pixels instead of byte-per-pixel loops — NEON-friendly and lighter on the shared memory bus that the CPU translation layers (FEXCore/Box64) and the Xclipse GPU compete for.
- The evshim shim resolved `/proc/self/fd/N` via procfs on **every** `read()`/`ioctl()` inside guest processes; the verdict is cached per fd and invalidated on close/open.
- ALSA pacer thread no longer requests max `SCHED_FIFO`, EGL display is resolved once across all `EGLImage` create/destroy calls, JNI method lookups run only once.
- Experimental Performance reports a unified-memory-aware VRAM cap (3/8 of total RAM, clamped 2–4 GB) when Max Device Memory is left unset, so games cannot over-commit shared RAM until Android kills Wine.

#### ARM64EC / FEXCore game compatibility (PES 2018 round)

- The builtin wined3d must run on the **GL renderer** (`Direct3D\renderer=gl`, chosen once from the Video tab): this Proton arm64ec build otherwise defaults wined3d to its Vulkan backend, whose swapchain path died with "Unsupported alpha mode" + a failed `vkDestroySurfaceKHR` assert on Xclipse devices. GL renders through zink into the wrapper ICD instead — matching the bionic base behavior.
- `extra_libs.tzst` (libGL.so.1, libglapi, vkBasalt) is now extracted whenever missing instead of only inside the experimental-BCN flow; without it the GL renderer failed with "Failed to load libGL → OpenGL support is disabled" and games page-faulted at adapter init.
- Dedicated **ARM64EC DXVK builds** are bundled and selectable (`2.3.1-arm64ec-gplasync`, `1.10.3-arm64ec-async`). Under ARM64EC Proton the plain x86_64 DXVK builds never load — games silently fell back to builtin wined3d no matter which DXVK version was chosen.
- FEXCore presets now match the bionic base: Intermediate sets `FEX_X87REDUCEDPRECISION=1`, Performance adds `FEX_DYNAMICL1CACHE=1` + `FEX_DISABLEL2CACHE=1`.
- Guest processes preload system `libjpeg.so` / `libcrypto.so` (with `/system_ext` and imagefs fallbacks), like the bionic base ships by default.
- DDraw wrapper gained a **None** option (restores the builtin ddraw.dll), usable alongside CnC-DDraw/Dd7To9/WineD3D.
- Guest stdout/stderr is piped to logcat again, so Wine debug channels (`+loaddll,+seh,err+all`) produce readable backend logs for troubleshooting.

## Previous release: WinXclipse v0.8.6

Version 0.8.6 builds on the large runtime and interface foundation introduced in v0.8.5. That release focused on external content management, Xclipse driver support, safer defaults, BCN texture handling, the in-session sidebar, diagnostics, and HUD accuracy.

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
- Settings, new containers, and new shortcuts start with the **Compatibility** FEXCore preset.
- VKD3D now starts as **None**, while remaining selectable from the combined DXVK + VKD3D configuration.
- **Show FPS** is enabled by default.
- ASTC and ETC2 transcoding start disabled, so texture transcoding is opt-in.

#### Xclipse GPU and BCN handling

- Added real GPU renderer detection with recognition for the Xclipse family, including Xclipse 920, 940, and 950.
- Replaced Qualcomm-specific wording in the visible driver workflow with Xclipse-oriented naming while preserving the underlying package-management compatibility.
- Replaced the BCN runtime layer with the custom Leegao BCN layer.
- Added the Mali reference wrapper as a separate **Wrapper-BCN** option without replacing the existing **Wrapper**.
- Native BCN controls are used by compatible wrappers, including Wrapper-BCN, GameNative, Kirimu, and Ref4ik-v6.
- Wrappers without native BCN controls are routed through the shared Leegao compute layer, so BCN is not tied to one wrapper.
- Ported the BCN activation flow used by the reference implementations, including automatic/full emulation, compute/software selection, cache control, ASTC transcoding, and ETC2 transcoding.
- Kirimu uses its working software BCN path. ASTC and ETC2 use the Mali-compatible Leegao layer independently of the selected wrapper.
- The Leegao layer is enabled only after both its library and Vulkan manifest are verified inside the runtime filesystem.
- Stale or incomplete layer files are removed so they cannot silently interfere with native wrapper BCN handling.
- Bundled wrappers are re-extracted after an application update, and a replaced user-installed wrapper is re-applied on its next launch.

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

Additional wrappers installed from **Downloads** appear immediately alongside the built-in entries and can also be removed there. Availability and compatibility depend on the device and selected container configuration.

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
- Ports from [Winlator Ludashi](https://github.com/StevenMXZ/Winlator-Ludashi)
- [ExynosTools](https://github.com/WearyConcern1165/ExynosTools) for Exynos/Xclipse driver resources
- [MdiEx](https://github.com/avavo/MdiEx) for Xclipse driver resources
- Wrappers from [Leegao](https://github.com/leegao/bionic-vulkan-wrapper), [GameNative](https://github.com/utkarshdalal/GameNative), [Ref4ik-v6](https://github.com/REF4IK/CronyX-), and Kirimu
- WinXclipse Exynos/Xclipse adaptation by Álvaro
