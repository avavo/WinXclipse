# Prepared Proton 9 prefix and RAM readings — 2026-11-02

## Observed on the S24+

The c8abc1f run at 11:12:28 used `Proton-9.0-arm64ec-0`. Wineboot took 34,036 ms, server shutdown 814/108 ms and component reapplication 14,069 ms. That launch created a new prefix. The previous registry optimization did not remove this reconstruction.

Container creation searched for `Proton-9.0-arm64ec-0_container_pattern.tzst`; the APK contains `proton-9.0-arm64ec_container_pattern.tzst`. The baked-in synthetic catalog profile has no external prefix pack, so this alias fell through to a raw empty registry and forced wineboot.

Baked-in runtime aliases now use the exact APK template. The disabled-update marker, registry headers/architecture and byte-identical ntdll, kernel32 and wineboot modules in both ARM64 and WoW64 are checked before recording the selected runtime fingerprint. A runtime change still invokes normal wineboot migration.

A scratch probe under the app UID used the real APK template registries and the installed Proton core modules. Adoption passed in 622/949 ms across two runs; subsequent preparation took 265/398 ms and did not execute Wine or the component callback. These are prefix validation/preparation timings, not complete desktop/game startup timings. No existing user prefix was replaced or reset.

## Follow-up: creation-to-launch alias invalidation

The a4293f8 launch at 11:45 exposed a second issue. Creation successfully adopted `proton-9.0-arm64ec`, but the Activity normalized its selection to `Proton-9.0-arm64ec-0` before launch. The raw selection string was part of the receipt fingerprint, so the launcher rejected the successful creation receipt. Wineboot took 20,958 ms and component reapplication 8,889 ms; the preparation phase total was 31,748 ms. The earlier scratch probe used the same identifier twice and did not cover this transition.

Fingerprint identities now normalize case and the default catalog build suffix for Wine/Proton. Nonzero builds, architecture/version suffixes, runtime paths and actual executable/setup bytes still distinguish runtimes. Successful receipts written with the old exact algorithm are migrated only when they match the current selected files. They do not force another Wine update after installing this APK.

The reuse also applies to downloaded Wine/Proton. A freshly extracted external prefix pack may skip its initial forced migration only if it already declares updates disabled, contains populated valid registries and has byte-identical core modules for the selected Windows architectures. External packs are checked before filling missing modules, so a partial pack cannot become eligible merely through DLL seeding. The baked-in APK template retains its existing verified seeding path. Raw packs still initialize once; subsequent launches reuse the successful receipt. Runtime setup paths follow the launcher's bin-parent layout, including nested package paths.

An Android scratch probe reproduced the previous APK's alias rejection while intercepting the redundant Wine command before execution. The corrected code accepted the real APK template in 263 ms, reused its receipt after the exact lowercase-to-catalog transition in 134 ms, and migrated an old successful receipt in 168 ms. No Wine/game process ran. These remain prefix-only timings, not a measurement of complete desktop startup. Host integration coverage creates, publishes and reloads containers from downloaded-pack fixtures, applies the Activity's actual catalog normalization, checks nested runtime layouts and rejects incomplete packs before seeding.

## RAM mismatch

Screenshots showed the HUD at 7.3 GB and the task manager at 6.82 / 10.94 GB. The HUD divided bytes by 10^9, while the manager divided by 2^30. Those used-memory numbers are equivalent; the shared GB label concealed the unit difference. A subsequent screenshot showed 61% in both views.

The HUD now reads Android ActivityManager memory information into one immutable SystemMemoryUsage sample. Both task-manager variants consume that same sample. Free/used RAM, byte labels and percentage alerts share its total, available bytes and rounded percentage. RAM quantities consistently use 2^30 bytes per GiB, two decimals and the same locale. The legacy HUD also uses this RAM formatting. Swap remains an independent /proc reading; denied swap access no longer erases valid RAM data. Missing RAM readings show N/A instead of a false zero.

Regression coverage checks template alias resolution through launch, verified-template adoption, downloaded-pack reuse, old-receipt migration, mismatch fallback, runtime-change migration, screenshot-scale RAM conversions and the Android-reader-to-task-meter path. Full test/build/signature/install results are recorded in local build metadata.
