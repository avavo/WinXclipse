# Proton startup and saved runtime defaults — 2026-11-02

## Comparison

Compared WinXclipse d592db9 with the local StevenMXZ/Winlator-Ludashi checkout at 1042ba5. Ludashi's GuestProgramLauncherComponent executes the selected Wine directly. WinXclipse additionally performs a selected-runtime prefix migration, reapplies managed components while Wine is stopped, and verifies the side-by-side Vulkan runtime before launching the desktop/game. A matching prepared-prefix receipt skips migration; the explicit unsupported Vulkan bridge result is already cached for six hours.

Existing S24+ logs from 10:50 showed wineboot -u at 12,931 ms, server stop/wait at 813/110 ms, then about 28 seconds until the baseline Vulkan phase (2,612 ms). These are historical phase timings, not an end-to-end before/after comparison or evidence that every later boot repeats migration.

The registry editor used for services/components/theme copied and rewrote the registry for every setting, including unchanged values. Service policy alone makes up to 75 setter calls; XAudio component configuration also changes many system.reg keys. This work runs before boot and again after a real runtime migration.

## Change

WineRegistryBatch streams the existing registry into one temporary file, applies the queued values and publishes after successful completion. It preserves unrelated sections, metadata, escaped names and continued hex data. Unchanged settings leave the original bytes and timestamp intact. The service policy still avoids creating nonexistent services/control sets; no compatibility checks or prefix migration were bypassed. New component/prefix timing logs distinguish cached preparation from actual migration.

On the S24+, an isolated 75-value policy benchmark on a synthetic 3,576,417-byte registry took 2,345/2,229 ms with the prior editor and 36/29 ms with the batch writer. Both methods ran on scratch files under the app cache, with the order alternated. This measures registry editing only; it does not establish the new complete Proton startup time. No Wine/game or DisplayX session was opened for this benchmark.

## Removed-runtime defaults

The new-container spinner reused retainSelection, which intentionally preserves missing versions for existing containers. With saved defaults, this reinserted a deleted Proton 9 as a phantom selectable row. Appearance in that row did not prove the runtime files had returned.

New/default containers now match their saved version against installed runtime rows, choose an installed replacement when necessary, and save that corrected default without changing other settings. Successful removal redirects matching saved defaults to the installed replacement. Removal failures are reported rather than removing only the catalog row. Existing container runtime selection is still preserved for diagnosis; runtime validation before prefix creation/launch prevents execution against a missing runtime.

## Validation

Regression coverage includes registry preservation, no-op timestamps, escaped/default values, continued hex replacement/removal, absent service keys, malformed files and saved-default runtime removal/selection. Full suite and APK validation are recorded in the local build metadata. End-to-end Proton boot timing and RE9/DisplayX remain unmeasured in this revision.
