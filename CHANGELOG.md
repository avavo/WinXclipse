# Changelog

All notable changes between WinXclipse releases, newest first.

## 0.9.6

### Installation and APK editions

- The APK signing key changed. Installations signed with the old key, including the original 0.9.5, require uninstalling the old app and reinstalling 0.9.6. Export saves and full containers, back up settings/control presets, and verify copies outside private app storage before uninstalling. Restore the backups after installation; community configuration ZIPs do not contain full containers or saves.
- WinXclipse, Antutu, Geekbench and PUBG provide the same features with separate Android package identities and app data. Recent local 0.9.6 builds already use the current key.

### Video, Vulkan & DisplayX

- Added a Vulkan compositor with FSR EASU/RCAS, HDR, FXAA, CRT, NTSC, Toon and compute Frame Generation.
- Kept the Android compositor separate from the Wine renderer: Vulkan/OpenGL outside, Vulkan/GL/EGL inside Video settings.
- Added Zink and Automatic OpenGL driver choices. Zink is the default; Automatic lets Mesa choose without forcing Zink.
- Added optional DisplayX AHardwareBuffer transport, disabled by default and available only when both the Android compositor and Wine renderer use Vulkan.
- Switching Wine to GL or EGL immediately clears DisplayX. Incompatible saved configurations are also checked before launch.
- Added separate surface-format preferences: BGRA by default for Vulkan and RGBA for DisplayX, with manual selection available.
- Added DisplayX controls for Bypass X11, Performance mode, Present at refresh rate, Submit every buffer and Precise presentation.
- Preserved standard X11/WSI fallback for unsupported paths. The HUD distinguishes actual DisplayX frames from a ready DisplayX session displaying X11/GDI content.
- Fixed DisplayX extension reporting so unsupported native-swapchain extensions do not prevent the compatible AHB path from being used.
- Correct Wine Vulkan child-window reparenting, coordinates and scene refresh while preserving the XID/drawable. Parent movement now updates descendants; InputOnly windows no longer enter composition. These fixes are locally verified without opening RE9; its black-screen report still needs game validation.
- Request the CPU read/write gralloc usage pair used by Mesa/Quasar for Xclipse interop, validate the allocated format/stride and keep rendering on the GPU. A standalone S24+ test passed with System and JimVulkan RADV in UNORM and mutable sRGB; full RE9/DXVK validation remains pending.
- Query Vulkan extensions and API support from the selected driver in an isolated process, including custom drivers.
- Set the default Vulkan version from the driver's reported API instead of its name. Added Vulkan 1.4 selection for drivers that report support.
- Added JimVulkan/radv-xclipse to driver sources and improved driver reimport and recovery.
- Enabled BCn support by default. Quasar, RADV and Phoenix default to None for BCn emulation.
- Display the real-device GPU choice as Xclipse while preserving its original function and saved identifier.
- Improved FSR scaling, viewport edges, cursor composition, Frame Generation history resets and graphics-resource cleanup.
- Request the appropriate high-refresh display mode for the app and game surface, including resume and surface recreation. Game FPS and the FPS limiter remain independent.

### Startup, Wine & Proton

- Preserve prepared prefixes across Wine/Proton catalog name changes, including downloaded runtimes and successful preparations from the previous APK. New containers also reuse complete compatible prefix packs supplied by downloaded runtimes; incomplete packs and actual runtime changes still receive their required initialization.

- Apply startup service, component and theme registry changes in a single pass per file, avoiding repeated rewrites of large Proton registries.
- Keep new/default containers on an installed runtime after removal. Verify runtime deletion and prevent an unavailable saved Proton 9 from reappearing in the new-container selector.

- Recompressed the bundled Proton TAR as Zstandard without changing its extracted contents, reducing decompression overhead.
- Buffered archive reads, reused extraction buffers and reduced writes for large zero-filled entries.
- Fixed first-install extraction progress for system files and bundled content packages. Percentages follow compressed reads and package installation; unmeasured preparation uses an indeterminate indicator instead of a frozen 0%.
- Validate archives through their footer and reject truncated or incomplete packages before recording success.
- Repair incomplete bundled runtimes through staging and rollback, preserving existing containers, user files and installed content.
- Prepare each prefix with its selected Wine/Proton, wait for that prefix's wineserver and reapply configured components before launch.
- Validate runtime architecture, launchers, modules and FEX bridge files. Remove stale FEX companion libraries when switching package types.
- Prepare and verify VulkanRT 1.3.283.0 side by side in the selected prefix while preserving the original Wine Vulkan integration and legacy recovery files.
- Remove the translated NSIS extraction step and two redundant server restarts from VulkanRT preparation. Use the already verified bundled DLLs and keep both architecture/device checks before recording success.
- Avoid repeating an explicitly unsupported Wine Vulkan compatibility check for up to six hours per runtime/configuration; other failures remain retryable.
- Skip Linux desktop-menu rebuilding during prefix setup, stream registry validation and avoid repeating Vulkan dependency probes when only presentation or RAM settings change. Record prefix phase timings for device diagnosis.
- Preserve launcher-to-game hand-off handling, startup diagnostics and cleanup of genuinely empty Wine sessions.

### Interface & In-game Sidebar

- Unify the RAM HUD and task manager on one Android reading and rounding rule. Show binary RAM quantities in GiB, including free/used formats, and keep unreadable swap data separate from RAM availability.

- Improved blurred surfaces across dialogs, selectors, context menus, startup progress and the in-game sidebar.
- Capture and freeze the sidebar background before opening its animation. Prime the first snapshot with the desktop, retain the previous blur on timeout and reject late captures that used to change the visible panel.
- Keep the blurred snapshot covering the entire sidebar during its slide, including areas outside the game surface; retain an opaque base instead of exposing clear strips.
- Apply rounded purple actions across Settings tools, ImageFS, shortcuts, downloads, HUD/Input/Display pages, control editors, terminal Execute and OK/Cancel buttons.
- Keep Settings toolbar/import/default-container icons unfilled; retain smaller outlined surfaces for gears/downloads in container fields, with centered icons and preserved padding.
- Keep Exit/Pause/Help equally spaced in the sidebar with complete icons, separate the HUD panel action from its appearance selector, and align container/shortcut field actions by moving Wrapper help into the caption.
- Edit FPS presets and the custom value in the sidebar page with live updates and X/back navigation, without a separate preset/custom popup.
- Separate community-export labels and inputs and match the three cover actions to the app style.
- Align Settings and controller switches/help icons, including BCn, with consistent control sizes and wrapping.
- Keep deleted control presets deleted across reloads, including an empty library. Add confirmed Delete all presets and Restore default presets, preserving existing custom/edited layouts during restore.
- Add Container settings below Sound to edit the defaults used by future new containers, without modifying existing containers or community imports.
- Improved text and icon contrast, rounded outlines, field heights, option spacing and storage/audio controls.
- Show the current selection in radio-style selectors and keep VSync values readable.
- Open HUD, Display, Input, logs, debug and terminal pages inside the sidebar, with X/back navigation.
- Apply in-session settings as edited and confirm pending values when closing, removing redundant OK/Cancel footers from these sidebar pages.
- Allow the HUD settings body to scroll fully and reduce the height of the power, pause and help footer.
- Restore a vertical RAM meter beside CPU information in Task Manager. Expand all CPU and process rows in one sidebar scroll surface.
- Improve shortcut editing menus, keep artwork previews proportional and make overflow icons readable.
- Center Create new in the add-container menu, wrap navigation labels and improve download/settings/help action sizing.
- Add English, Portuguese and Spanish, with automatic system-language detection and a persistent manual choice.
- Keep the classic interface active and preserve the unfinished console interface separately.
- Remove automatic app-update prompts and the obsolete 0.9.6 startup announcement.

### HUD & Measurements

- Expanded native HUD presets, configurable panels and combinations with the DXVK HUD.
- Added actual DisplayX/X11 transport information.
- Improved per-core CPU usage, clock readings, core names, GPU usage/frequency and unavailable-sensor recovery.
- Added optional GPU and CPU/AP temperature readings and swap used/total. Unavailable measurements are shown as unavailable.
- Added Samsung sensor handling for the S24+, including the readable GPU sensor and AP thermistor; AP readings are identified separately from CPU die temperature.
- Corrected battery-current unit handling on affected Samsung devices to restore power readings instead of rounding them to 0 W.
- Improved device/SoC/GPU identification and RAM display formats.
- Added gameplay measurements with average FPS, 1% lows, ranges, battery/power information and TXT reports.
- Keep real and generated FPS separate and exclude paused periods from gameplay measurements.

### CPU, Memory & Experimental Options

- Enable Sync CPU with Wine and WoW64 performance-core pinning by default while preserving explicit choices.
- Move experimental controls below GStreamer, with their experimental status visible instead of hiding them in a separate dialog.
- Expose RAM preparation, aggressive RAM, cache, VRAM limits, policy engine and optimized BCn controls together.
- Show the optimized BCn layer only while BCn is enabled. Disable experimental BCn also turns off the optimized layer.
- Replace temporary RAM-pressure allocations during startup with bounded allocator cleanup.
- Apply the aggressive allocator policy inside guest processes when enabled, including child processes, and preserve other preload libraries.
- Preserve explicit environment-variable removals in both container defaults and individual containers; old migrations no longer restore deleted RAM Fix entries.
- Keep RAM preparation and aggressive memory behavior optional; they do not guarantee a lower total Android RAM percentage or faster startup in every game.

### Downloads, Runtimes & Community Configurations

- Use FEXCore 2610 and Box64 0.4.5 as new defaults while retaining older downloadable versions.
- Match the FEX catalog to all 15 published WCP manifests, including 2508 and the differently named 2412/2507/2609 packages. Replace dead 2607/2608 links and remove the unavailable online 2610 link; the bundled runtime remains available.
- Remove duplicate FEX version entries caused by bundled, installed and online catalog identifiers, preserving genuinely different builds.
- Combine Wine and Proton in one download category while retaining version, build and architecture information.
- Add a shared download queue with pause, resume, retry and cancel, remote-file validation and safer concurrent cache handling.
- Resolve exact community dependencies across runtimes, translators, drivers and wrappers before creating a container.
- Review the selected versions, game name and cover before import, export, sharing or publication.
- Stage imports and keep durable checkpoints to recover after interruption without creating duplicate containers or replacing unrelated files.
- Add hosted community publication and moderation, verified legacy configurations, offline catalog snapshots and ZIP sharing.
- Publish automatically after configuration export. OK closes the result; Cancel sending configuration to community asks Yes/No and removes the actual publication while keeping the local ZIP.
- Match named DXVK/VKD3D WCP assets to their embedded manifests without mixing architectures or variants. Remove the deleted ARM64EC 3.0a download and refresh old cached catalogs.
- Add live release, stars, community membership and license badges to the README with WinXclipse branding.

### File Manager, Backup & Stability

- Start File Manager on a drive/storage selection screen and show internal storage in GB, with an optional percentage display.
- Browse Android document trees without requiring a direct filesystem path, with persistent permissions and supported file actions.
- Share the clipboard across local drives and document storage. Stage transfers, verify copied data and preserve sources until a move succeeds.
- Improve cancellation, replacement backup and rollback for file transfers, subject to the document provider's capabilities.
- Fix backup restoration edge cases involving source ownership, truncation, self-copy and interrupted document streams.
- Improve physical mouse/keyboard detection, relative motion, wheel handling, pointer capture and controller cleanup.
- Validate complete gamepad profiles before applying them and save mappings atomically in UTF-8.
- Improve PulseAudio reconnection, audio-route handling and pause/underrun recovery.
- Validate graphics-wrapper payloads and restore previous files after failed changes instead of leaving a partial installation.

### Experimental status

DisplayX, Frame Generation, BCn layers, memory policies, wrappers and game compatibility vary by device, GPU driver, Android firmware, runtime and game. Wine's GDI desktop can still use X11 with DisplayX enabled; the HUD reports that distinction.

Native DisplayX transport, synchronization and RGB frame readback were checked on the Galaxy S24+. Full Proton/DXVK gameplay validation remains pending. The user confirmed 120 Hz operation on the device; that does not imply every game renders at 120 FPS.

The S24+ archive benchmark measured decompression plus SHA-256 at 28.270 s for XZ and 2.178 s for Zstandard. This is not a complete app/container boot measurement, and the compressed Proton asset is about 34 MB larger.

### Additional fixes and maintenance

- File Manager replaces an existing destination instead of creating a suffixed duplicate. Symlink-aware sizes keep progress accurate for large saves and game folders.
- Shortcut lifecycle detection distinguishes the normal exit of start.exe from the game process and allows 30 seconds for slow launcher-to-game hand-offs.
- Prefix compatibility repair performs a one-time cleanup of legacy runtime copies without replacing broad DLL sets or mixing Proton generations.
- Normal and Essential startup keep RpcSs available for COM-based games/installers; X server multi-locks use a stable acquisition order.
- Imported Vulkan settings are checked against the selected driver and device, including clamping unsupported Vulkan 1.4 requests to 1.3.
- Wine/Proton, FEXCore, Box64, DXVK and VKD3D selectors resolve installed payloads instead of treating remote catalog rows as installed packages.
- Remove the root docs directory and update repository references. Host tests consistently select the desktop Conscrypt provider across all four APK identities.

### Credits

- Winlator by Brunodev85
- Winlator CMOD by coffincolors and Pipetto-crypto
- Winlator Glibc by longjunyu2
- Winlator OpenXR by lvonasek
- Wine
- Box86/Box64 by ptitSeb
- FEX-Emu
- Mesa, DXVK, VKD3D, D8VK and CNC DDraw
- AMD FidelityFX Super Resolution
- Souza controls branch
- Winlator Mali
- Winlator Ludashi
- Bannerlator HUD references
- ExynosTools / Soula
- GameNative Team
- Leegao
- Ref4ik-v6
- Kirimu — integrated Vulkan / BCn wrapper
- Jim / JimVulkan — RADV for Xclipse (https://github.com/JimVulkan/radv-xclipse)
- JavaSteam by Longi94 and contributors
- heroic-gogdl by Heroic Games Launcher contributors
- gbe_fork / Goldberg Steam Emulator
- Zstandard
- zstd-jni by Luben Karavelov
- Winlator Xclipse adaptation by Álvaro

Additional license information is listed in THIRD_PARTY_NOTICES.md. Project credits are also available in the app's About screen.

## 0.9.5

### New

- Downloads now lives inside the container editor: Wine/Proton, wrappers/drivers, DXVK/VKD3D, Box64/WOWBox64 and FEXCore each have a compact contextual download icon that opens a category-locked install/import/remove catalog without discarding unsaved container changes. Remote entries are visually faded until installed, and the DXVK/VKD3D icons live beside their version selectors inside the configuration dialog.
- Settings can expose an optional live backend-log tile beside Debug Terminal in the Wine sidebar. It captures app logcat and Wine process output without ADB and supports pause, clear, save and disable actions.
- Wrapper configuration exposes Vulkan 1.4 when Android reports Vulkan 1.4 hardware support.
- Community Configs catalog backed by the GitHub `community-configs` release, with automatic ZIP download, exact/fuzzy local-content resolution, persistent staging, and offline fallback through a saved index and validated cache.
- Community game search in the toolbar filters cards by normalized game name, including case, spacing, punctuation, and Arabic/Roman sequel-number equivalence.
- Community exports carry the required game name, Discord handle, expected FPS, fixed detected phone model, hardware metadata, notes, content requirements, an optional embedded cover, and exactly one compatibility state (`Boots but crashes`, `Menu only`, or `Playable`) in a version-free ZIP filename.
- VSync modes (`100%`, `50%`, and `Off`) are available for containers, shortcuts, and the live Display sidebar.
- External Android storage and configured Wine drive roots are available from File Manager.
- Input Controls now includes controller auto-grab (plug and play), master vibration, and vibration testing. Touchscreen-button haptics and their switch are removed; physical-controller game rumble remains available.
- The Winlator HUD includes an independent battery-percentage option.
- The first visit to Community Configs explains how to export a tested config and links directly to the project Discord for submission.
- The About dialog now exposes the project Discord beside the GitHub link.
- Experimental Performance adds a per-game Translation Turbo switch and a manual 6144 MB unified-memory VRAM cap.
- `Wrapper-Default` keeps the hybrid Wrapper-Default + July13 stack for normal use. ASTC/ETC2 transcode sessions use the proven Winlator Mali pair instead (verified on Xclipse 940: hundreds of ETC2 and 106 ASTC transcodes at ~1.2-2.2 GB/s in RE3 Remake).
- Experimental Apex/GLES 3.1 frame generation adds three distinct profiles,
  Automatic target-FPS mode, fixed 1.5x-4x multipliers, and a compute fallback.
  It is disabled by default, can be configured per container, and runs after
  the existing FSR/HDR compositor passes.
- Containers start with `DXVK_CONFIG_FILE=Z:/usr/dxvk.conf` and a replaceable generated `dxvk.conf`; the RAM Fix stages the effective file to that guest-visible path at launch. A custom config can still be imported below the DX wrapper selector, travels with duplicate/export, and is inherited by shortcuts.
- Imported `dxvk.conf` files are checked against the known-bad Mali/OpJuegos template: `relaxedBarriers=True` (grey roads and map corruption in RE2/GTA V), `useRawSsbo=True`, sub-1024 MB memory caps, invalid keys, active HUD, and zeroed device IDs are flagged, with a one-tap sanitized import offered.
- Shortcuts can import their own `dxvk.conf` from the same DXVK + VKD3D dialog, stored as a sidecar beside the shortcut's `.desktop` file. It takes precedence over the container and global configs, travels with shortcut clones, and is renamed/removed together with the shortcut.
- Settings offers an optional alternative WinXclipse launcher icon. Android switches between launcher aliases without killing the app, while the default icon remains available at any time.
- Graphics Driver Configuration now contains the Experimental BCN master and a separate `Use optimized Xclipse BCN layer` switch. The established Leegao path remains the default; the Xclipse-tuned archive is loaded only when explicitly selected.
- Optional Wine lifecycle diagnostics now create a per-container directory under `Downloads/WinXclipse/logs` with a general session report plus separate DXVK and VKD3D side logs.
- Virtual gamepad releases now publish a complete neutral snapshot on touch-up, cancellation, and window focus loss, preventing RE Engine titles such as Resident Evil 2 from keeping a stick or button held after a missed release event.

### Changed

- Boolean controls in container and shortcut Input, Display, DXVK, Video, driver/wrapper/BCN, Performance and HUD configuration now use compact, aligned, non-animated switches with a purple enabled state. CPU-affinity core selectors retain their original checkbox layout. The Wine wallpaper selector previews the actual light/dark/custom wallpaper and still supports replacing or removing it.
- Graphics Driver now precedes Wrapper in container and shortcut settings. Both editors keep their selected names readable and expose individual compact download icons for wrappers, drivers, Box64/WOWBox64 and FEXCore; shortcut DXVK/VKD3D downloads also open correctly through the dialog-themed context.
- The Wine sidebar uses a larger power symbol for Exit, sized consistently with Pause and Help, and the standalone Downloads drawer entry was removed in favor of the container-integrated catalog.
- Touch controls incorporate Souza's Final refinements: lighter borders, larger high-contrast labels, button/stick glow, the original Souza D-pad halo, a 0.25 analog dead zone, and 2.70 stick response. Trackpads and RTS keyboard rows now use the matching border/glow weight, while the two virtual-gamepad left sticks move slightly away from the screen edge.
- The former BCN stack is now the clearly named `Wrapper-Default`. CMOD v1 and Kirimu are built in; Leegao, CMOD v2, GameNative and Ref4ik-v6 are installed on demand from Downloads.
- Galaxy Tab S10 FE/FE+ Wi-Fi and 5G models (SM-X520, SM-X526B, SM-X620, and SM-X626B) are recognized as Exynos 1580 / Xclipse 540, including early-start fallback detection when Android reports a generic GLES renderer.
- Frame Generation now lives in Video Configuration for both containers and shortcuts, with shortcut-specific overrides. It exposes three distinct profiles instead of overlapping labels: Fast (1/8-resolution motion search), Balanced (1/4), and Quality (1/2 plus conservative fallback). Existing legacy and Ultra Quality values migrate to the nearest safe tier; separate `?` help is available for the feature and profile, plus FSR Upscale and vkBasalt. The Low Latency extrapolation mode was removed; frame generation always uses interpolation-only blending.
- Fixed multipliers use 0.5x steps from 1.5x through 4x; Automatic mode uses the entered target FPS. The artifact-prone 4.5x and 5x choices were removed.
- The compact in-session layout places FPS Limiter beside Frame Gen, with half-width HUD and Display tiles below. Frame Gen remains off by default, starts with the Fast/2x preset when first enabled, is available whenever the Wine renderer is Vulkan, and its sidebar toggle persists the last enabled/disabled state for the active container or shortcut.
- Winlator HUD can show the container graphics API and the phone's physical GPU. Its compact headline is again `Apex <source FPS × effective multiplier> (<multiplier>x)`, so it is not incorrectly capped by panel Hz or cluttered with a second `real` value. Optional rows expose Frame Generation latency, shader state/failure and actual `GEN FPS`; `GEN 0 FPS (no output)` exposes an enabled generator that is not producing synthetic frames. `ApexTelemetry` writes source, presented, generated, multiplier and latency samples to logcat.
- Frame Generation again uses the fixed GLES Compute path from the older stable APK. Backend selection and all native/automatic `libapex` activation were removed, and frame generation always uses interpolation-only blending.
- The Frame Gen sidebar toggle now follows its synchronous requested state, so disabling it cannot be inverted by a still-queued startup activation; runtime backend failures also clear the toggle.
- Existing Wrapper-BCN and Wrapper-Xclipse containers migrate to Wrapper-Default and refresh the selected coherent wrapper/layer pair once after updating.
- Community entries are grouped by normalized game identity. Matching configs share the first embedded cover found before browser artwork is requested, support a user-selected cover, and expose device choices plus on-demand container details in an English AMOLED interface.
- Shortcut and Community Config artwork is clipped to rounded top corners and joins the lower text surface cleanly. Community cards repeat the cover as a dark blurred label background, show compatibility instead of the phone model, and keep the config count at the lower-left above the label. Shortcut artwork, community cards, and the community install dialog share the purple-bordered blurred dialog style.
- Every dialog, popup menu, and dropdown uses the purple-bordered style in both dark and light themes; light dialogs dim the background less so the blur never goes too dark.
- Newly created shortcuts always start in Browser artwork mode with an immediate cover fetch instead of waiting for a later list bind.
- The Community Config install dialog shows a rounded 2:3 cover banner, fits a slightly narrower popup, and uses consistent English action labels.
- Missing DXVK, Box64, FEXCore, or VKD3D content required by a community config is downloaded from the remote catalog and installed automatically during import instead of prompting; runtimes stay manual because of their size. The remaining "Content not found" prompts share the purple-bordered style and point to Downloads.
- Container pickers run or create directly when only one container exists instead of showing a single-choice dialog.
- The About dialog uses the purple-bordered style with top-pinned scrollable content, and long shortcut names are truncated to one line in settings.
- The backend Logs dialog uses the purple-bordered style, follows the app theme instead of the system one, tints its toolbar purple, and can save the log to Downloads/WinXclipse/logs besides sharing it.
- The in-session sidebar, task manager, and CPU/RAM monitor follow the app theme instead of staying dark, and base dialog top padding is tighter.
- Community metadata preserves Android's exact Samsung regional model suffix and normalizes known devices to their commercial name, Exynos SoC and Xclipse GPU. The Mortal Sin SM-X520 release config is corrected to Galaxy Tab S10 FE / Exynos 1580 / Xclipse 540.
- Bundled Box64 is `0.4.3-260519-024717c`; old `0.4.4` selections migrate to the bundled version and installed-content detection uses the exact content identity.
- New containers default to the Intermediate FEXCore preset; XInput, DInput, and Exclusive XInput remain opt-in; the WoW64 CPU-list fallback uses all available cores; and lower-memory devices select stronger RAM-reclamation baselines.
- MangoHUD mode uses the stable Android-native HUD path adapted from Bannerlator instead of injecting an unavailable guest Vulkan layer.
- Runtime/content names are deduplicated and existing containers migrate automatically when an installed profile's old identifier is normalized.
- Existing Community Config ZIPs recover their selected external Xclipse driver automatically: installed packages are checked first, then Downloads, then the maintained driver catalog. Older exports such as Xclipse 920 Old do not need to be recreated.
- Existing Community Config ZIPs recover downloadable wrappers by stable id from Downloads or the Wrappers release catalog, so CMOD v2, GameNative, and Ref4ik-v6 do not require configs to be recreated. CMOD v1 remains a bundled component.
- Wine sessions rotate between normal and reverse landscape, allowing either phone edge to face up while portrait remains blocked.
- The Video dialog documents Mailbox, FIFO, Immediate, and Relaxed behavior; all bundled Vulkan wrappers were checked for the four modes.
- The sidebar FPS Limiter tile toggles its last configured limit, while the three-dot menu offers 24, 30, 45, 60, 75, 90, and 120 FPS plus a Custom 15-240 FPS value; the obsolete Unlimited item is removed.
- The native HUD shows the physical GPU above the SoC and removes a redundant leading `Samsung` from the GPU name.
- The update prompt uses the WinXclipse AMOLED dialog and the app version is displayed as `0.9.5`.
- FSR strength and upscale quality are configured in Video Configuration for containers and shortcuts; the live Display sidebar presents the active state without silently replacing the saved quality preset, while texture filtering remains available as a live control.
- The Display sidebar exposes FXAA, CRT, Toon, and NTSC effects directly, while brightness, contrast, and gamma remain grouped under Color Custom.
- vkBasalt CAS/DLS sharpening, strength, and denoise are available in Video Configuration and the in-session Display panel. Display changes are saved for the shortcut/container and applied on its next launch.
- The native HUD starts in monochrome mode with Show FPS, wrapper, GPU, CPU, RAM, battery, border, and SoC information selected; a one-time 0.9.5 migration also enables Show FPS for an older saved HUD mask, after which the user's choice is preserved.
- Experimental Performance no longer enables any sub-option implicitly. WoW64 performance-core pinning and Translation Turbo are shown beside the other system controls, remain off until selected, and still require the Experimental Performance master at runtime.
- Graphics Driver, DXVK and Wine video-memory selectors now expose 6144, 8192, 10240 and 12288 MB. The former 4092 MB compatibility value migrates to the correct 4096 MB value.
- The Wine desktop Light/Dark mode follows the effective WinXclipse app theme by default, while explicit Light and Dark overrides remain available per container. Its palette and bundled wallpaper now resolve from the same mode, preventing a light wallpaper on an otherwise dark Wine desktop; a user-selected custom wallpaper continues to take precedence.
- Wine image wallpapers are generated at the container desktop resolution, center-cropped to preserve their aspect ratio, and explicitly stretched by Wine instead of appearing as a centered 480p rectangle over the solid background color.

### Performance

- Wine 2D navigation uploads only the accumulated changed rectangle of a drawable instead of re-uploading a full window for small menu/highlight changes; padded-stride uploads are packed correctly.
- Present notifications no longer hold the drawable render lock while sending X11 events, reducing input/render contention.
- Opaque Wine/game windows and full-screen post-processing passes render with blending disabled, and content fully hidden below the highest opaque full-screen window is skipped while later menus and popups remain visible.
- Repeated startup service rewrites and temporary diagnostic logging were removed from the normal launch path.
- Normal and Essential Wine startup disable PlugPlay, RpcSs and tablet input. Aggressive keeps controller-critical PlugPlay available but disables RpcSs and tablet input; Proton builds without the tablet-input service key also receive a bounded startup cleanup for `tabtip.exe`, matching the current seven-to-eight-process baseline without breaking controller discovery or rumble.
- Container filesystem deletion runs off the UI thread.
- The Wine/Proton catalog is populated immediately from its local cache and throttles network refreshes; verified DOS drive maps and the shared PulseAudio payload are reused between launches.
- Storage-provider copies use buffered 64 KiB transfers instead of 1 KiB chunks, improving config/driver import and large-file I/O.
- The Shortcuts screen opens from the sidebar without crawling every Wine drive on the UI thread; existing entries and artwork load through a fast background path while new `.lnk` files are discovered separately.
- New Box86/Box64 and FEXCore **Turbo (Aggressive)** presets provide larger translation blocks and relaxed compatibility checks without changing Stability, Compatibility, Intermediate, or Performance. Experimental Translation Turbo can apply the same opt-in group per game without destroying the selected preset.
- DXVK 1.x state caches, DXVK 2.x shader caches, and VKD3D-Proton shader caches now share a persistent fast-internal-storage directory; disabled renderer logs no longer create files.

### Fixed

- Wine Light/Dark switching now reapplies the complete palette before every Wine startup and writes the missing `AppWorkSpace` color, so Explorer/file-manager surfaces cannot keep an old workspace color while only the wallpaper changes.
- Wine lifecycle diagnostics are isolated by launch generation: late stdout, exit code 137, and PIDs from a previously closed session can no longer contaminate the next report. Reports now state whether an explicit guest/GPU crash signature was captured and include Android's available/total memory and low-memory state at shutdown.
- Removed the GDI renderer choice and migrate stale GDI selections to Vulkan. The container renderer is authoritative and is written back immediately before launch, so selecting Vulkan or OpenGL no longer reverts after leaving Wine.
- Frame Generation is forced off for OpenGL/GDI Wine renderers, uses the Android display clock to schedule generated frames, and falls back to direct rendering if its GLES resources fail, preventing the experimental path from closing incompatible containers. The compositor now explicitly requests an EGL 3.1 context on Samsung drivers instead of receiving ES 3.0 from an ambiguous major-only request, and transient pre-surface capability failures are no longer cached. The motion-vector compute shader declares explicit high precision for sampler/image opaque types, fixing the Xclipse `image2D: No precision specified` failure that disabled Apex when Display settings were confirmed.
- Persisted Frame Generation is armed after the EGL surface stabilizes, preventing shortcuts from entering a reopen crash loop after Frame Generation was enabled and the game exited.
- Apex now spends only the generated-frame budget earned by actual game Presents, including fractional 1.5x cadence. It stops scheduling when that budget is empty instead of redrawing one stale texture forever, preventing the GPU-starvation freeze that left the Android activity and game process alive after RE2 stopped updating. GLES dispatch errors are validated initially and sampled periodically instead of calling `glGetError()` after every source frame and synchronizing the mobile GPU queue.
- Apex pacing never sleeps on a pending real game Present. Generated draws follow the Android display clock against a finite per-Present budget and stop scheduling when the budget is empty, preserving the game's base FPS under GPU pressure.
- Apex motion-vector images use GLES 3.1-compatible RGBA8 storage and a shader-image plus texture-fetch memory barrier, preventing stale or vendor-rejected compute output. Motion vectors are encoded/clamped, confidence and disocclusion fallback are stricter, and out-of-bounds warps return to the unwarped temporal blend instead of producing large artifacts.
- Frame Generation discards GPU-side motion history and waits for two fresh real frames after a loading stall, pause, surface/window rebuild, z-order/geometry change, or long frame gap. Setup/runtime failures now remove the effect, persist Frame Gen as disabled, update the sidebar, record backend/config state in lifecycle diagnostics, and recover through direct rendering without closing Wine.
- The editable Input Controls template no longer crashes after selecting a newer Souza button icon; the complete icon range is cached and invalid icons are ignored safely.
- Virtual controls now track Android's stable pointer IDs instead of transient pointer indexes. Cancelling a multitouch gesture releases every owned button, D-pad, trackpad and analog axis, range controls cancel without generating a tap, and the neutral state is published before the overlay is hidden. This prevents controls from becoming unresponsive or leaving a character walking in the last direction during long sessions.
- The touchscreen timeout reads its current setting consistently and never expires while a control or touchpad gesture is still held. Disabling it no longer leaves a stale five-second hide callback armed.
- Physical-controller rumble parses the complete Linux `eventN` device suffix, routes dual motors independently when available, throttles repeated effects, clamps effectively infinite durations and explicitly stops every motor when vibration or the session is disabled. This prevents ADB/USB device renumbering and missed stop events from routing vibration incorrectly or leaving it stuck.
- Browser artwork search for shortcuts cleans launcher/renderer suffixes, ranks normalized title matches instead of blindly choosing the first autocomplete result, and tries additional matching games when a result has no portrait cover.
- OpenGL/WineD3D and GDI sessions extract their required system GL libraries independently from the experimental BCN path, fixing containers that closed before reaching the desktop.
- ASTC and ETC2 transcode requests activate the BCN compute layer even when BCn emulation is set to None or Partial, and the disk-cache switch now reaches both the wrapper and Leegao layer.
- Explicit ASTC/ETC2 requests force decode instead of allowing wrapper driver-ID auto-detection to skip it and report `ARMED`, `LOADED`, `ACTIVE`, or a specific capability/encode/resource error in the HUD. `ACTIVE` is confirmed by a logged transcode operation, including the layer profile-line format (`[   encode_astc]`/`[   encode_etc2]`), with compute-pipeline creation as a fallback for wrapper builds that omit per-texture telemetry. Layer mapping alone remains `LOADED`. Transcode sessions now extract the proven Winlator Mali wrapper/layer pair instead of the hybrid Wrapper-Default + July13 stack: the new wrapper hides ASTC unless `WRAPPER_BCN_ASTC` is set (then black ASTC), and its tracked BCN copy path (`bufTracked`/`bcn_gpu_inflight`) deadlocks RE3 on Xclipse on the first emulated texture in every transcode mode (silent hang, 0% CPU, reproduced 4x) — a layer the old wrapper has no such tracking and runs clean. `WRAPPER_BCN_ASTC=1` is still exported for the new wrapper, stale wrapper BCN/ASTC vars are cleared on every boot, and containers migrate between pairs automatically via revision markers. The non-transcode path keeps the new pair and its fixes.
- The BCN quality preset is exported as `BCN_QUALITY_PRESET` whenever it is not `auto`, matching Mali 1.2 (the proven layer generation reads it; `auto` leaves the choice to the layer).
- Kirimu keeps the exact legacy bundled wrapper but no longer receives Leegao-only cache, compute-disable, ASTC/ETC2 transcode or Xclipse optimization controls. Its integrated BCN route uses only variables implemented by Kirimu, with safe device creation and a bounded 128 MB staging arena to prevent repeated allocation growth during failed texture uploads.
- Enabling the Xclipse-optimized BCN option now always selects its matching archive, including BCN emulation sessions without a separately selected ASTC/ETC2 transcode mode.
- Shortcut sessions no longer close merely because `start.exe` returns before a child launcher/game. They close after five continuous seconds with no non-base guest process, while Wine services and crash-defender/reporting processes are ignored for the idle decision. Optional lifecycle diagnostics save the close reason, process snapshot, non-standard processes and recent guest output under `Downloads/WinXclipse/logs`.
- Community configs no longer disappear between catalog selection and import when another screen clears its own cache. GitHub API rate limits fall back to the public assets page, saved index, or verified cached ZIPs.
- Imported community containers no longer reuse transient install state that could make them open and immediately close; default/bundled content IDs no longer produce false missing-content prompts.
- Wine-created `.lnk` shortcuts resolve quoted, relative, environment, Unix, and Wine paths correctly and refresh their names, executable targets, and artwork.
- Touchscreen mouse handling, pointer grabs, press/release ordering, dragging, Wine context menus, and shortcut creation were aligned with the working Winlator Mali behavior.
- Proton x86_64 startup preserves the shared Box64 executable, launches the installed Wine binary by absolute path, and keeps guest libraries out of Android's native `LD_LIBRARY_PATH`.
- Conventional x86/x86_64 Wine no longer receives ARM64EC DXVK DLLs. The selector filters incompatible entries, launch migrates old incompatible defaults in memory, and missing DXVK/VKD3D DLLs are reinstalled instead of trusting a stale marker.
- Disabling VKD3D restores Wine's D3D12 files after DXVK extraction, preventing legacy DXVK packs from silently reintroducing a stale `d3d12.dll`.
- Pure win32 prefixes no longer create a synthetic `syswow64`; WoW64 prefixes still populate required 32-bit DLLs.
- FSR and other post-processing effects retry offscreen targets with core RGBA when a driver rejects BGRA, then fall back to direct rendering instead of a black screen if framebuffer creation still fails.
- Container IDs are selected from both memory and the filesystem, preventing an imported or newly created container from overwriting `xuser-1`; valid on-disk metadata is preserved during defensive saves.
- The startup controller-assignment notice was removed completely.
- Rockstar titles no longer tear across the screen when panning: the selected present mode is honored independently from 100%/50%/Off pacing, mailbox keeps the newest complete image without serializing the game like FIFO, Android compositor frames are synchronized to the display, and the unsafe single mutable AHardwareBuffer presentation path was removed.
- Live/sidebar VSync values no longer remain as stale overrides after editing a container or shortcut, and `Off` keeps guest FPS uncapped without allowing partial Android compositor frames.
- Mailbox, FIFO, and Relaxed keep required presentation waits even if an old advanced config requested they be bypassed; only Immediate can opt into that tearing-prone fast path.
- VKD3D-Proton receives the selected Vulkan present mode explicitly, including the correct `FIFO_RELAXED` spelling, so DX12 follows the same Mailbox/FIFO/Immediate/Relaxed choice as DXVK and the wrapper.
- FSR 1.1 now lowers the guest desktop resolution before EASU instead of upscaling an already rendered full-size image, so GPU-bound games receive the intended shading reduction. Live quality changes resize the X desktop, root window, and active game window and rebuild their render targets.
- Live FSR changes no longer leave fullscreen games clipped, zoomed, or surrounded by unintended black bars.
- Auto refresh rate requests the panel's highest supported display mode, while explicit 60/90/120/144 Hz choices resolve to the corresponding mode when available.
- The HUD RAM blink and warning thresholds are consistent across memory tiers at 90% and 93% respectively.
- Wine registry self-healing validates the real `WINE REGISTRY Version N` header and recreates invalid win64 headers for every supported prefix source, preventing the recurring invalid-registry/32-bit-wineserver startup failure.
- Synthesized Wine/Proton profiles skip unusable non-archive `prefixPack` files instead of failing during container creation.
- The container Wine/Proton picker no longer lists the APK-bundled Proton 9 twice when its managed content profile is also installed; bundled and version-code `0` identities now share one row.
- PulseAudio stays connected during screen recording, ADB/USB announcements and playback-route changes. Recorder-only/source-only events no longer restart the daemon; when Android disconnects the AAudio stream, a debounced native bridge replaces only `module-aaudio-sink`, restores volume/mute state, moves existing Wine streams and removes stale sink modules.
- The external wrapper catalog now queries the existing `wrappers` release tag instead of the nonexistent `wrappers-9.5` tag, restoring CMOD v2, GameNative and Ref4ik-v6 discovery and downloads. Leegao moved out of the APK into the same remote catalog and appears in the graphics-wrapper selector only after installation.
- File sharing and generated frontend launch metadata use the active application ID for every package flavor, and the default frontend export path is consistently branded as `Downloads/WinXclipse/Frontend`.
- Required D8VK 1.0 and DXVK 1.10.3 async/ARM64EC archives are included in the source assets again, matching the versions exposed by the selectors and defaults.
- New containers preconfigure `DXVK_CONFIG_FILE=Z:/usr/dxvk.conf`. With RAM Fix enabled and no override, launch writes a real guest-visible file containing safe DXVK defaults and conservative reported-memory values, covering legacy bundled DXVK versions that do not reliably consume the inline `DXVK_CONFIG` form.
- With DXVK + RAM Fix, Graphics Driver Max Device Memory and Experimental Performance VRAM limits now upper-bound only the memory reported in `dxvk.conf`; `WRAPPER_VMEM_MAX_SIZE` and `UTIL_LAYER_VMEM_MAX_SIZE` are removed so they cannot impose a real Vulkan-heap cap. Logs from GTA V showed 2,154 allocation failures after DXVK reached 1,638 MB — its 80% allocator threshold on a forced 2,048 MB heap — followed by a command-stream-thread exception. When RAM Fix is disabled, an explicitly selected hard cap continues to work normally.
- `VKD3D_SHADER_MODEL=6_6` is only set when VKD3D is actually enabled, instead of leaking into D3D11 sessions and legacy VKD3D setups.
- Shortcut sessions no longer linger forever when the game never spawns an application process: Wine's debugger (`winedbg`) counts as a base process, stale `shortcut_path` launches are tracked by the lifecycle probe, and a launcher stub with no app for 2 minutes closes the session with a logged reason.
- The DXVK + VKD3D gear now exposes `RAM Fix`, enabled by default. For GTA V it applies the tested memory-streaming flags (`-maxStreamedGrid 10 -minStreamedGrid 5 -hdr 0 -percentvidmem 100`) and merges them into `commandline.txt`, preventing missing textures and city-driving OOM freezes/crashes. Disabling the option removes only the exact managed values; custom values remain untouched.
- Optional GTA V optimization preset inside the DXVK + VKD3D dialog (Mali-style potato mode: DX10 renderer, lowest qualities, slashed density/LOD), applied only when enabled and without user-defined `-nomemrestrict`.
- Container, shortcut and imported `dxvk.conf` saves are now flushed and atomically replaced instead of overwriting live files in place. Cross-process file locks and concurrent merge preserve newer on-disk settings, latest-state `.container`/shortcut recovery mirrors restore missing or malformed indexes, and an existing Wine prefix is brought back with safe defaults when an older build left no usable index—preventing Android process or cache eviction from reverting, truncating or hiding confirmed game configuration.
- Processor-affinity controls now form centered vertical grids in both container and shortcut settings: octa-core devices use aligned rows of four, while deca-core devices use aligned rows of five instead of placing the rows side by side.
- The xidi controller fix (`dinput.dll`/`dinput8.dll`/`xidi.ini`) is installed only when DirectInput is actually enabled, and never for RAGE targets (GTA V uses XInput and the overwrite broke its boot). Existing prefixes that received the unconditional install are migrated back to Wine's builtin DLLs automatically.
- RAM Fix now covers the whole RAGE launch chain: `GTAVLauncher.exe` and `Language Selector.exe` receive the tested memory-streaming flags and `commandline.txt` like direct `PlayGTAV.exe`/`GTA5.exe` launches do. Previously the default repack shortcut skipped every protection and crashed "out of nowhere" while streaming the early van mission or driving through the city. Launch flags still go only to binaries that accept them; `commandline.txt`, which RAGE always reads from the game directory, covers every launcher.
- RAGE sessions using RAM Fix Auto report a uniform 512 MB pool through `dxgi.maxDeviceMemory`, `dxgi.maxSharedMemory`, `d3d9.maxDeviceMemory`, and `d3d9.maxAvailableMemory`. Community testing confirmed the game then holds total RAM around 80% instead of the 85-90% band where Android's `lmkd` reaps it; the streaming flags from RAM Fix remain required alongside the reported pool.
- RAM Fix is configurable and general: the DXVK + VKD3D dialog offers `Auto`/512/1024/1536/2048/3072/4096 MB per container and shortcut. Explicit pools apply uniformly to all four DXVK/D3D9 reported-memory keys; Auto keeps the conservative RAM-tier fallback for other games. The generated container file is marked so WinXclipse may refresh it, but an imported shortcut/container config or `dxvk.conf` beside the game replaces it completely (priority: shortcut, container, game folder, legacy global). Manual `DXVK_CONFIG` and non-managed `DXVK_CONFIG_FILE` Environment Variables remain the highest-priority escape hatch.
- Wine prefixes re-sync with the active runtime on every boot (`PrefixDllResync`, file operations only, never `wineboot`): missing `system32`/`syswow64` DLLs are restored, and after a runtime swap present-but-diverged builtin DLLs are refreshed unless a native override is registered in `user.reg`. DLLs owned by versioned flows (DXVK/VKD3D, xidi, ddraw) are never touched. The sync is versioned per container (`lastPrefixRuntimeId`), capped at 20 seconds, and any failure is fail-open so boot never blocks — fixing games that "stopped opening out of nowhere" after a runtime change without requiring a runtime reinstall. Saves are untouched (only prefix Windows DLLs are read/written).
- Launching a shortcut whose executable no longer exists (renamed/moved folder, different case on ext4) now shows the missing path in a Toast immediately instead of sitting through the silent 2-minute launcher-stub timeout before the watchdog closes the session.
- Two-stick drift fixed: the touchpad received the full `MotionEvent` once per unclaimed pointer during `ACTION_MOVE`, duplicating deltas whenever both sticks were held and making the view rotate on its own. It is now dispatched once per event. Gamepad bindings also release unconditionally on touch-up/cancel, and pending touches are cancelled when the controls detach or the profile switches, so axes cannot stay latched.

## 0.9

### New

- **AMD FSR 1.1 support**: full FSR1 pipeline (EASU upscaling + RCAS sharpening) ported from the official AMD shader (`ffx_fsr1.h` v1.20210629). Configurable per container in the Display section and live-applied from the in-session sidebar Display menu; EASU always runs first in the effect chain. Five quality presets from Fidelity to Ultra, each forcing a matching internal render scale (1.5x/1.7x/2.0x), with letterbox-aware EASU mapping of the scene content region.
- Video configuration tab for containers and shortcuts: renderer selection (Vulkan/OpenGL/GDI) as the primary graphics backend switch, GPU name, present mode, texture filtering (now hosting the FSR entry), and a red/blue channel swap option.
- Audio driver configuration dialog with volume control; MIDI soundfont selection moved inside it. PulseAudio becomes the default driver.
- Shortcut artwork system with three configurable sources — **Browser** (SteamGridDB search with EXE-icon fallback, default on first boot), **EXE icon**, and **Custom** — settable globally or overridden per shortcut.
- New dark glassy touchscreen control style: gradient button bodies with thin light borders, four-petal D-pad with outward chevron arrows and per-direction press highlighting, tick-marked analog sticks, and a matching trackpad.
- Complete gamepad skin set for the touchscreen controls as image assets — face buttons (A/B/X/Y), shoulders (L1/R1), triggers (L2/R2), stick clicks (L3/R3), Start/Select, a four-way D-pad, an analog stick base ring with tick marks, and a trackpad — selectable per element in the controls editor (D-pad, stick, and trackpad elements accept skins) and pre-applied to both bundled Virtual Gamepad profiles.
- **Refresh rate** option (Auto/60/90/120/144 Hz) in the video configuration dialog for containers and shortcuts; requested from the display when the game session starts.
- Update checks run on every launch, prompting at most once every 4 days when a newer GitHub release is available.
- Exclusive XInput mode for containers and shortcuts; when off, XInput and DInput can be enabled together with the simultaneous-use warning.
- Contextual **?** help buttons across theme, rotation lock, performance, DXVK/VKD3D, video, and graphics dialogs.
- Experimental Performance tuning dialog with individual feature switches; companion stacks integrated into our own build instead of prebuilt binaries: the MdiEx runtime (device classification, Xclipse policy engine, scheduler nice-biasing, memory shim in passive mode), the NRAMV unified-memory manager driven from Java, and LayerCache Helix built as our own Vulkan layer library for pipeline warmup and texture caching.
- Bundled packages: `Dxvk-2.6.2-arm64ec-gplasync.wcp`, `dxvk-1.7.3-async`, and `vkd3d-proton-3.0.1b.wcp` install automatically on first boot; dedicated ARM64EC DXVK builds selectable (2.3.1-arm64ec-gplasync, 1.10.3-arm64ec-async).
- DDraw wrapper gained a **None** option restoring the builtin ddraw.dll, alongside CnC-DDraw/Dd7To9/WineD3D.
- Guest stdout/stderr piped to logcat again, so Wine debug channels produce readable backend logs.
- HUD setting to disable the blinking RAM warning and its high-memory dialog.
- HUD gains SOC load and GPU temperature elements selectable in the HUD config dialog.
- New screen sizes for modern tall panels: 2340x1080 (19.5:9), 2560x1440 (16:9), and 3120x1440 (19.5:9) selectable in container and shortcut settings.

### Changed

- Max Device Memory exists only in the Graphics Driver configuration; the DDraw wrapper moved inside the combined DXVK + VKD3D dialog (default WineD3D); default DXVK changed to async-1.10.3; BCN layer files left by v0.8.x are cleaned up on update.
- Aggressive startup selection protects winebus/winehid/MountMgr/PlugPlay while disabling non-critical Wine services; corrected the Wine `Ndis` registry key.
- Separate WoW64 CPU list restored so 32-bit processes can be pinned to different cores than 64-bit processes.
- FEXCore presets match the bionic base: Intermediate sets `FEX_X87REDUCEDPRECISION=1`; Performance adds `FEX_DYNAMICL1CACHE=1` + `FEX_DISABLEL2CACHE=1`.
- BCn emulation controls match the Winlator-Mali reference exactly (none/partial/full/auto → `WRAPPER_EMULATE_BCN` 0/1/2/3); wrappers with native BCN handling (GameNative, Kirimu, Ref4ik-v6) no longer load the shared Leegao layer on top of their own implementation, and stale layer files are removed when switching; Kirimu locks its software-only profile.
- Per-model Exynos/Xclipse tuning: Experimental Performance VRAM cap follows WGP count (2048 MB fixed on 530/540/550/920, RAM-following on 940/950, full 4092 MB on 960); BCn backend defaults to software on RDNA2 and compute on RDNA3/RDNA4; low-WGP RDNA2 defaults to BCn-to-ASTC transcoding; CPU cluster detection reads cpufreq data so WoW64 pinning lands on performance cores, with the LITTLE-less Exynos 2600 allowed to use all ten cores.
- Vendored internals rebranded (`rox_`/`wxp_` prefixes, file renames) with wire-format env keys preserved.
- First boot installs base files immediately; storage permissions are requested after installation completes.
- Follow-system theme enabled by default; landscape lock option added.
- Texture filter gains a fourth **None** mode (bilinear/nearest/FSR/None); FSR choices now persist as `rendererFilterMode`/`fsrUpscale`/`fsrQuality` runtime extras, with the legacy `fsrMode` key only read once to migrate old configs (quality modes imply EASU upscale).
- Gyroscope input is enabled by default.
- LayerCache Helix (Cache Xclipse pipeline/texture caches) defaults to off under Experimental Performance; enable it in the tuning dialog.
- About dialog and release notes credit FEX-Emu and AMD FidelityFX Super Resolution.

### Performance

- Control overlay shaders are cached instead of allocated per frame; rumble/turbo poller reduced from ~200 to ~60 wakeups/s; playtime persisted every 30 s; cover art decoded downsampled; failed artwork lookups remembered per session; debug logging removed from controller motion-event and stylus hover hot paths.
- Native X11 blits (`copyAreaOp`, `fillRect`, `drawLine`) process whole 32-bit pixels instead of byte loops — NEON-friendly and lighter on the shared LPDDR bus.
- The evshim input shim caches its `/proc/self/fd/N` procfs verdict per file descriptor instead of resolving it on every read/ioctl.
- ALSA pacer thread no longer requests maximum SCHED_FIFO; EGL display resolved once across EGLImage calls; JNI method lookups run once.
- Unified-memory-aware VRAM cap under Experimental Performance when Max Device Memory is unset (2048 MB on 8 GB-class devices, 4092 MB on 12 GB-class), backed by known per-SoC RAM tiers.
- Opt-in `vblank_mode=0` skips vsync waits for OpenGL/zink titles; per-frame `glGetError` validation is debug-only now; GPU renderer detection results are cached.

### Fixed

- ARM64EC guests dying silently before creating any window under Proton arm64ec + FEXCore: the launcher requested the legacy `libarm64ecfex.dll` bridge instead of `libwow64fex.dll`.
- Builtin wined3d pinned to the GL renderer under ARM64EC Proton (its Vulkan swapchain path died with "Unsupported alpha mode" + failed `vkDestroySurfaceKHR` on Xclipse devices); `extra_libs.tzst` extracted whenever missing so the GL renderer can load libGL.
- Bundled content installer re-ran on every launch (success-marker check dropped during refactor); uninstall clears markers again.
- memshim malloc interpose leaking into libwinlator.so caused an emutls stack overflow (macro name mismatch MDIEX_ vs WXP_).
- Inverted exclusive XInput behavior fixed: exclusive mode locks XInput-only; controller-fix installation verifies all three files including dinput.dll before marking complete, retrying otherwise.
- Startup Selection inverted condition (Essential vs Aggressive behaved identically); playtime autosave double-scheduling after onResume.
- Video tab renderer choice persists (startup no longer forces GL back); GPU Name no longer leaks from the Wine-tab spoof spinner; VKD3D extraction matches "-0"-suffixed bundled archives; dead DXVK 1.7.2 duplicate entries cleaned up automatically.
- NPE confirming container creation and opening Video Config in create-container mode; missing/corrupted JSON assets and local files surface as handled errors instead of NullPointerExceptions; downloads no longer crash when the Contents screen closes mid-transfer.
- ? help popups measure wrapped text correctly and use themed backgrounds on dark/light; HUD °C mojibake; follow-system-theme checkbox default mismatch between UI and runtime.
- Container failing to start on Android 10+ with `error=13, Permission denied` when launching the Wine/PulseAudio binaries: `targetSdkVersion` lowered to 28, keeping exec() of binaries inside the app data directory allowed under the Android W^X policy.
- Downloads screen offered removal of the APK-embedded bundles (Box64/DXVK/FEXCore/VKD3D installed on first boot), which containers depend on: removal is no longer offered for embedded content (Info stays), and every bundled install is recorded so future bundles are protected automatically.
- Proton catalog entries showing blank names (the proton-11.0-2 sdk35 packages) or raw file names: bundled catalog metadata is now authoritative, giving clean names to 11.0-2 (arm64ec/x86_64) and 9.0-x86_64.
- Wine/Proton `.tzst` archives saved straight into Downloads (no embedded content profile) install normally now: a profile is synthesized from the archive's bin/lib layout, and the import picker accepts every common zstd MIME type.
- Xclipse driver download catalog sources releases from ExynosTools and the WinXclipse `drivers_0.9` release (the retired MdiEx repository and its cached entries are dropped).
- First-boot progress moves continuously through imagefs extraction (78%), Wine runtime install (93%) and finalization (96–100%) instead of appearing stalled.
- X server hardening: DestroySubWindows no longer falls through to ReparentWindow (protocol desync); SYNC AwaitFence blocks without deadlocking the whole server; GrabPointer reads its event-mask from the correct offset so grabs keep delivering button/motion events; malformed client requests (bad enum values, out-of-range indices, oversized properties) return protocol errors instead of killing every connection; large replies survive partial socket writes.
- Imported containers keep their original configuration — the copied `.container` file is loaded instead of being overwritten with defaults; the DDraw wrapper selection no longer clobbers the DXVK/VKD3D config when loading; content application copies via temp+rename so a failed install never deletes working DLLs; pinned-tag driver catalogs list correctly.
- Wine registry editor performs complete reads before writing and only replaces system.reg/user.reg with a fully valid clone.
- Input fixes: multi-binding elements no longer lose their last binding on load; range-button bindings are restored on touch up instead of being wiped into the saved profile; controller axes are clamped after a sensitivity boost (sticks no longer invert at the extreme); disconnected controllers no longer crash the bindings screen.
- A game exiting normally now tears down the X/audio/handler components and saves playtime exactly like the Exit action does; shortcut extras (execDelay, inputType, sharpness, rcfileId, controlsProfile) parse defensively instead of crashing at launch; several background-thread guards across Saves/Containers/Contents/Settings screens prevent crashes when leaving mid-operation.
- Containers created from raw Wine/Proton `.tzst` downloads boot correctly: the prefix is now built from the bundled Proton pattern (a full win64 prefix with valid registry headers) instead of the shared imagefs overlay, which left system.reg/user.reg invalid and made wine fail with "64-bit installation ... 32-bit wineserver".
- An out-of-spec PutImage no longer sends BadMatch to the client — Wine treats that as fatal and tore down every session ~2s after start; inconsistent payloads are logged and skipped instead, and depth-1 cursor/mask blits use the packed planar size.
- Guest stdout/stderr and the process exit code are always mirrored to logcat under the `WineProc` tag (previously they only appeared with the wine-debug setting enabled), making startup failures diagnosable without changing any setting.
- The fakeinput bridge library copy is size-verified on every launch with one retry; a truncated copy used to be LD_PRELOAD'd into wineserver and killed it with SIGBUS.

## 0.8.6

### New

- Downloads area (renamed from Contents) with dynamically refreshed Xclipse driver catalogs from ExynosTools and MdiEx releases, plus custom wrapper installation from a local file or HTTP/HTTPS URL (`.tzst`, `.tstz`, `.tzts`, `.zst`, or direct `.so`), named by the user as `Wrapper-Name`; remote catalogs stay cached when refresh fails.
- Real GPU renderer detection recognizing the Xclipse family (530, 540, 550, 920, 940, 950) with AMD RDNA generation mapping.
- Replaced the BCn runtime layer with the custom Leegao layer and added the Mali reference wrapper as a separate **Wrapper-BCN** option; wrappers without native BCN controls route through the shared Leegao compute layer, with the full activation flow ported (automatic/full emulation, compute/software selection, cache control, ASTC/ETC2 transcoding); layer enabled only after library and manifest verification, with stale files removed.
- Reworked in-session sidebar: time/battery header, pause/resume, help, terminal, and power-style exit actions; grouped input options; dedicated Controller action toggling touchscreen controls; Virtual Gamepad 2 profile; direct HUD enable/disable with separate config; FPS limiter (Unlimited/30/45/60/90/120) applied at guest presentation stage.
- Lightweight expandable Task Manager in the sidebar: total CPU and per-core frequencies, RAM usage, process list with PID/memory, and window focus/terminate/CPU-affinity actions.
- Opt-in Experimental Performance checkbox applying compatibility/performance environment flags only when selected.
- Three installable package identities (Main `com.win.xclipse`, AnTuTu, Geekbench 6) with providers keyed to the active application ID.

### Changed

- Runtime cleanup: Box64 `0.4.3-260519` bundled (older removed), FEXCore 2608 default (2505/2507 removed), DXVK `1.11.1-sarek` added while 2.2.116 and bundled GPLAsync variants removed; new Settings/containers/shortcuts start with the Compatibility FEXCore preset; VKD3D starts None; Show FPS enabled by default; ASTC/ETC2 transcoding opt-in.
- Bundled wrappers re-extracted after an application update; replaced user-installed wrappers re-applied on next launch.

### Fixed

- HUD renderer/API detection now reports DXVK/VKD3D/WineD3D/Vulkan/OpenGL from the active runtime instead of forced fallbacks, and wrapper families separately from the graphics API; unrelated destroyed windows no longer clear HUD renderer state.
- Battery power reporting uses real charge-counter and voltage data when exposed.
- Repeated online artwork retries while scrolling; recycled-bitmap risk between artwork download and reload; corrupted stored shortcut inputs handled safely.

## 0.8.5

### New

- Dynamic WinXclipse release catalogs for Wine/Proton, FEXCore, Box64, DXVK, and VKD3D packages, installed from `.wcp`, `.wcp.xz`, and compatible archives, with numeric download/install progress.
- Proton 10/11 and ARM64EC runtime handling improvements.
- FEXCore preset management (create/rename/duplicate/delete/import/export) selectable in Settings, Containers, and Shortcuts.
- Combined DXVK + VKD3D configuration; advanced wrapper configuration with Vulkan information, Present Modes, memory controls, and texture-transcoding options.
- Winlator HUD and MangoHud selection; automatic update checking through GitHub releases.
- New visual identity: AMOLED dark mode, ice-white light mode, purple accents; reorganized app/container navigation; USB-C/file-provider fixes and file-manager performance improvements.

## 0.7.6

- First organized source-based build: earlier APK-only modifications moved into the source tree and the project identity was established.
- Added the Xclipse GPU Drivers area, backend-log fixes, driver-selector cleanup, and Exynos/Xclipse-oriented metadata.

## 0.7.5

- Initial WinXclipse visual identity; expanded the original wrapper selection through APK-level modifications.
