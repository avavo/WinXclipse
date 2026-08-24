# WinXclipse

WinXclipse is an experimental Android project based on Winlator CMOD and adapted for Samsung Exynos devices with Xclipse GPUs.

## What is WinXclipse?

WinXclipse allows Windows applications and games to run on Android by combining:

- Wine and Proton runtimes, including ARM64EC builds
- CPU translation layers: FEXCore and Box64
- Graphics stacks: DXVK, VKD3D-Proton, and several Vulkan-based wrappers (Leegao, Kirimu, GameNative, Ref4ik-v6 families, among others)
- Container management for isolated runtime configurations, input controls, diagnostics, and external content delivery

Development is focused on the Exynos/Xclipse platform. Every supported GPU pairing — from Xclipse 530 (RDNA2) through 920, 940/950 (RDNA3) to 960 (RDNA4) — receives dedicated tuning for BCn texture handling, unified-memory-aware VRAM caps, and CPU cluster scheduling on big.LITTLE/DynamIQ Exynos SoCs.

See [CHANGELOG.md](CHANGELOG.md) for what changed between releases.

## Disclaimer

WinXclipse is experimental. Performance, stability, graphics-driver behavior, and game compatibility can vary according to the device, runtime, wrapper, driver, and container configuration.

Use it at your own risk and test each configuration individually.

## Downloads

Runtime packages (Wine/Proton, FEXCore, Box64, DXVK, VKD3D) and Xclipse drivers are discovered dynamically from GitHub releases, without requiring a new APK for every uploaded asset:

- WinXclipse releases: https://github.com/avavo/WinXclipse/releases
- ExynosTools: https://github.com/WearyConcern1165/ExynosTools/releases
- MdiEx: https://github.com/avavo/MdiEx/releases

Only install packages intended for your architecture, GPU, and runtime type. An entry appearing in Downloads does not guarantee that it is compatible with every device or game.

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

WinXclipse Exynos/Xclipse adaptation by Álvaro
