# TODO implementation and validation

17 October 2026. See [TODO.md](../TODO.md) for individual completion states and
[product decisions](product-decisions.md) for the choices made during implementation.

## Build checks

The ordinary `com.openfit.debug` debug build and optimized, unsigned `com.openfit` release build
compile. JVM verification reports **136 tests, zero failures** across 31 suites. Android lint reports
**zero errors, 73 warnings**; a passing lint task does not mean all warnings are resolved.

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest assembleRelease lintDebug
```

The release artifact is `app/build/outputs/apk/release/app-release-unsigned.apk`. It is not signed
for distribution or uploaded. Owner signing variables and the remaining Console work are described
in [release-checklist.md](release-checklist.md).

## Isolated Android regressions

On API 36, **27 distinct Android checks pass**: 14 store/migration/sync tests and the end-to-end
deletion test in the full run, followed by **12/12 UI tests with zero failures** in the final UI run.
The UI rerun follows corrections to fixture scene restoration and cached accessibility ancestors.
The breathing test also verifies the corrected production timer deadline across recreation.

Device tests use `com.openfit.validation.debug`, guarded by the instrumentation runner before any
fixture executes. They use synthetic records and injectable Health Connect clients. Ordinary debug
and release app data are not cleared or replaced. The fixture activity exists only when building
with `-PopenfitValidation=true`.

```powershell
.\tools\validate-device.ps1 -Adb '<SDK>\platform-tools\adb.exe' -Serial '<device serial>'
```

This builds, installs and runs the validation APKs. The script checks the test summary as well as
adb's exit status, because `am instrument` can exit successfully while reporting failed tests.
Validation builds share the debug output path; rebuild `assembleDebug` without the property to
restore the normal debug artifact afterwards. The script leaves the isolated packages installed.

| Coverage | Assertions |
| --- | --- |
| Raw store | Newest-first paging; save complete days before quota failure; resume without recopying completed days; fill once and force refresh |
| Local totals | Daily/hourly/minute/workout shares, including intervals across midnight; local reads do not call the fake provider |
| Changes | Old deletion IDs map to original interval dates; pending changes survive replacement of the store before a successful copy |
| Time zones | UTC to Los Angeles moves saved records and hourly totals locally; no remote reads or leftover staging rows |
| Derived caches | Room persistence, invalidation and rejection of values computed before a sync |
| Database upgrade | Manually created version-1 SQLite database migrates through schema 5, preserving a saved screen and validating the final Room schema |
| Sync | History 480 days old, upgrade from import version 1, quota interruption/resume, newly granted type, three-day refresh plus changed history, provider wipe/revocation and priority takeover |
| Customization | Light/dark rendering, Off, slot and section moves, preset choice, hidden cards, added tabs, Save versus Close and draft recreation |
| Onboarding | Unavailable/update-required provider, denied permission result, Not now, quota pause and Continue in the background without cancelling the import |
| Navigation | Detail/back scroll, tab switch/recreation and period selection after a corrupt local total causes a failed load |
| Wellness UI | Sleep Planner saves its window; breathing starts, pauses, resets and keeps elapsed time across recreation |
| Local deletion | Actual MainActivity deletion stops work/imports, clears local health/profile/goals/layout/journal/cloud state, preserves quota and starts onboarding |

The Android 13 Samsung device passed the earlier store/migration/sync/deletion and customization
checks, with focused planner, period-label and breathing checks. It disconnected before the final
expanded suite. The final expanded tests run on the existing API 36 emulator in a temporary,
read-only AVD session. Both environments use fake providers for these regressions.

## Limits and remaining checks

- Real Android 14+ Health Connect permission dialogs, grants and provider imports remain unverified.
  The onboarding registry supplies controlled permission results; it does not click the system dialog.
- The physical phone did not contain more than one year of source history. The 480-day fixture
  verifies the copy/resume logic but cannot establish behavior of every wearable or source app.
- Ordinary-app background permission and an actual hourly worker execution still need observation.
- Activity recreation is covered; actual process death and a signed optimized-app smoke test remain.
- Google reconciliation requests are tested with a fake transport. A live duplicate-containing account
  and production OAuth verification remain pending. The optional source is retained as requested.
- Source independence and fresh paired algorithm comparisons are open. Shared Git-history cleanup
  is explicitly deferred by the owner.
  Existing RMS measurements were not relabelled as newly rerun results.
- Publisher details, public privacy-policy hosting and publication were deferred by the owner.

Build logs and device screenshots are local artifacts under ignored `build/`; they are not store
screenshots or real-user health fixtures. No APK was installed over the ordinary openFit app.
