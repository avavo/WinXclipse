# WinXclipse

WinXclipse is an experimental project focused on adapting the Winlator CMOD build for Samsung Exynos devices with Xclipse GPUs.

## Goal

The goal of this project is to provide an organized base for Exynos/Xclipse testing, with future focus on presets, documentation, MdiEx/ExynosTools integration, and compatibility-oriented adjustments.

## Disclaimer

This is an experimental project. Nothing here guarantees performance, stability, or universal compatibility.

Use it at your own risk.

## Credits

WinXclipse is based on the work of Winlator, Winlator CMOD, and their respective developers and contributors.

## WinXclipse v0.7.5

WinXclipse v0.7.5 introduced visual identity changes and expanded wrapper support.

### Available Wrappers

- Wrapper
- Wrapper-v2
- Wrapper-Leegao
- Wrapper-EV1
- Wrapper-EV2
- Wrapper-Kirimu
- Wrapper-LD24

### Implementation

- The Graphics Driver list was expanded through smali changes.
- The `.tzst` packages were created from the original `wrapper.tzst`.
- Only `usr/lib/libvulkan_wrapper.so` changes between wrapper variants.
- The original wrapper structure was preserved.
- The visual name and logo were corrected to WinXclipse.

### Status

Functionally experimental.
