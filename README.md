# WinXclipse  
  
WinXclipse is an Android project based on Winlator CMOD and adapted for Samsung Exynos devices with Xclipse GPUs.  
  
## What is WinXclipse?  
  
WinXclipse allows Windows applications and games to run on Android by combining:  
  
- Wine and Proton runtimes, including ARM64EC builds  
- CPU translation layers: FEXCore and Box64  
- Graphics stacks: DXVK, VKD3D-Proton, the built-in hybrid Default/CMOD v1/Kirimu wrappers, and downloadable CMOD v2/GameNative/Ref4ik-v6 packages
- Container management for isolated runtime configurations, input controls, diagnostics, and external content delivery  
  
Development is focused on the Exynos/Xclipse platform. Every supported GPU pairing — from Xclipse 530 (RDNA2) through 920, 940/950 (RDNA3) to 960 (RDNA4) — receives dedicated tuning for BCn texture handling, unified-memory-aware VRAM caps, and CPU cluster scheduling on big.LITTLE/DynamIQ Exynos SoCs.  

## Current 0.9.5 behavior

- Apex Frame Generation is an experimental Vulkan-only compositor option. It requests an explicit OpenGL ES 3.1 context and offers GLES Compute, native `libapex`, and Automatic backends. Fast, Balanced, and Quality profiles, Low Latency, and 1.5x-10x/Automatic output modes are documented through the adjacent `?` buttons in Video Configuration.
- The HUD measures completed real/generated compositor draws, can report Apex latency/backend/failures, and starts with Show FPS enabled. GPU, SoC, API/wrapper, and effective ASTC/ETC2 state can be shown independently.
- Wrapper-Default uses the WinXclipse hybrid stack normally. When its opt-in ASTC or ETC2 transcode is selected, WinXclipse switches the wrapper and Leegao layer together to the exact compatibility pair from the tested Winlator Mali APK; disabling transcode restores the hybrid pair.
- Touchscreen buttons do not vibrate. Physical-controller rumble remains available through the master/per-player vibration controls and test action.
- FSR Upscale reduces the guest render resolution before EASU; vkBasalt adds a Vulkan sharpening pass. Their image-quality and performance trade-offs are described by the `?` buttons in Video Configuration.
  
See [CHANGELOG.md](CHANGELOG.md) for what changed between releases.  
  
## Disclaimer  
  
WinXclipse is experimental. Performance, stability, graphics-driver behavior, and game compatibility can vary according to the device, runtime, wrapper, driver, and container configuration.  
  
Use it at your own risk and test each configuration individually.  
  
## Credits  
  
WinXclipse builds upon the work of:  
  
- [Winlator](https://github.com/brunodev85/winlator) by Brunodev85  
- [Winlator CMOD](https://github.com/coffincolors/winlator/tree/cmod_v13.1) by coffincolors and Pipetto-crypto  
- Winlator Glibc by longjunyu2  
- Winlator OpenXR by lvonasek  
- [Winlator Mali](https://github.com/GunaCharanTeja/WinlatorMali) for ports and implementation references  
- [Winlator Ludashi](https://github.com/StevenMXZ/Winlator-Ludashi) for ports and implementation references  
- [ExynosTools](https://github.com/WearyConcern1165/ExynosTools) for Exynos/Xclipse driver resources  
- [MdiEx](https://github.com/avavo/MdiEx) for Xclipse driver resources  
- Wrappers from [Leegao](https://github.com/leegao/bionic-vulkan-wrapper), [GameNative](https://github.com/utkarshdalal/GameNative), [Ref4ik-v6](https://github.com/REF4IK/CronyX-), and Kirimu  
  
Winlator Xclipse adaptation by Álvaro
