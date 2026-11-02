# Prepared Proton 9 prefix and RAM readings — 2026-11-02

## Observed on the S24+

The c8abc1f run at 11:12:28 used `Proton-9.0-arm64ec-0`. Wineboot took 34,036 ms, server shutdown 814/108 ms and component reapplication 14,069 ms. That launch created a new prefix. The previous registry optimization did not remove this reconstruction.

Container creation searched for `Proton-9.0-arm64ec-0_container_pattern.tzst`; the APK contains `proton-9.0-arm64ec_container_pattern.tzst`. The baked-in synthetic catalog profile has no external prefix pack, so this alias fell through to a raw empty registry and forced wineboot.

Baked-in runtime aliases now use the exact APK template. Only a newly extracted prepared APK template for the baked-in Proton is eligible for adoption. The disabled-update marker, registry headers/architecture and byte-identical ntdll, kernel32 and wineboot modules in both ARM64 and WoW64 are checked before recording the selected runtime fingerprint. A runtime change still invokes normal wineboot migration. Other downloaded prefixes are not implicitly marked ready.

A scratch probe under the app UID used the real APK template registries and the installed Proton core modules. Adoption passed in 622/949 ms across two runs; subsequent preparation took 265/398 ms and did not execute Wine or the component callback. These are prefix validation/preparation timings, not complete desktop/game startup timings. No existing user prefix was replaced or reset.

## RAM mismatch

Screenshots showed the HUD at 7.3 GB and the task manager at 6.82 / 10.94 GB. The HUD divided bytes by 10^9, while the manager divided by 2^30. Those used-memory numbers are equivalent; the shared GB label concealed the unit difference. A subsequent screenshot showed 61% in both views.

The HUD now reads Android ActivityManager memory information into one immutable SystemMemoryUsage sample. Both task-manager variants consume that same sample. Free/used RAM, byte labels and percentage alerts share its total, available bytes and rounded percentage. RAM quantities consistently use 2^30 bytes per GiB, two decimals and the same locale. The legacy HUD also uses this RAM formatting. Swap remains an independent /proc reading; denied swap access no longer erases valid RAM data. Missing RAM readings show N/A instead of a false zero.

Regression coverage checks template alias resolution, verified-template adoption, mismatch fallback, runtime-change migration, screenshot-scale RAM conversions and the Android-reader-to-task-meter path. Full test/build/signature/install results are recorded in local build metadata.
