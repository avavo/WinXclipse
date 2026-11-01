# WinXclipse  
  
WinXclipse is an Android project based on Winlator CMOD and adapted for Samsung Exynos devices with Xclipse GPUs.  
  
## What is WinXclipse?  
  
WinXclipse allows Windows applications and games to run on Android by combining:  
  
- Wine and Proton runtimes, including ARM64EC builds  
- CPU translation layers: FEXCore and Box64  
- Graphics stacks: DXVK, VKD3D-Proton, the built-in hybrid Default/CMOD v1/Kirimu wrappers, and downloadable CMOD v2/GameNative/Ref4ik-v6 packages
- Container management for isolated runtime configurations, input controls, diagnostics, and external content delivery  
  
Development is focused on the Exynos/Xclipse platform. Every supported GPU pairing — from Xclipse 530 (RDNA2) through 920, 940/950 (RDNA3) to 960 (RDNA4) — receives dedicated tuning for BCn texture handling, unified-memory-aware VRAM caps, and CPU cluster scheduling on big.LITTLE/DynamIQ Exynos SoCs.  

See [CHANGELOG.md](CHANGELOG.md) for detailed behavior notes.
  
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
- Souza controls branch for touch-control refinements
- [Bannerlator](https://github.com/The412Banner/Bannerlator) for native HUD architecture references
- [ExynosTools](https://github.com/WearyConcern1165/ExynosTools) for Exynos/Xclipse driver resources  
- Wrappers from [Leegao](https://github.com/leegao/bionic-vulkan-wrapper), [GameNative](https://github.com/utkarshdalal/GameNative), and [Ref4ik-v6](https://github.com/REF4IK/CronyX-)
- Kirimu for the integrated Vulkan / BCn wrapper
- [RADV for Xclipse](https://github.com/JimVulkan/radv-xclipse) by Jim / JimVulkan for the downloadable Xclipse Vulkan driver
- Steam sign-in and library code adapted from [GameNative](https://github.com/utkarshdalal/GameNative) (GPL-3.0), built on [JavaSteam](https://github.com/Longi94/JavaSteam) (MIT)
- GOG sign-in, library and downloads adapted from [GameNative](https://github.com/utkarshdalal/GameNative) and [heroic-gogdl](https://github.com/Heroic-Games-Launcher/heroic-gogdl) (GPL-3.0)
- [Wine](https://www.winehq.org), [Box86/Box64](https://github.com/ptitSeb/box64) by ptitSeb and [FEX-Emu](https://github.com/FEX-Emu/FEX)
- [Mesa](https://www.mesa3d.org), [DXVK](https://github.com/doitsujin/dxvk) and [VKD3D](https://gitlab.winehq.org/wine/vkd3d)
- [AMD FidelityFX Super Resolution](https://github.com/GPUOpen-Effects/FidelityFX-FSR)
- [Zstandard](https://github.com/facebook/zstd) and Luben Karavelov's [zstd-jni](https://github.com/luben/zstd-jni) bindings for archive compression and extraction
- [gbe_fork](https://github.com/Detanup01/gbe_fork) / Goldberg Steam Emulator
  
Winlator Xclipse adaptation by Álvaro

## License

WinXclipse is distributed under the [GNU General Public License v3.0](LICENSE), so that store
integrations from GameNative (GPL-3.0) can be shared with the project. Code inherited from Winlator
remains available under its original MIT license, kept in [LICENSES/MIT-Winlator.txt](LICENSES/MIT-Winlator.txt).
Third-party components and their licenses are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
