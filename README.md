# openFit

An Android dashboard for activity, sleep and recovery. Your copied records, profile, goals and
journal stay in the app’s private storage on your phone. No openFit account is needed. An optional
Google Health connection downloads additional history directly from Google.

## Data flow

Health Connect → `HealthSync` → Room copy (`HealthStore`) → pages and calculated estimates.

`HealthSync` imports the latest 365 days first, saves progress and continues older available history
in the background. App open and pull-to-refresh reread recent days and dates reported by the changes
feed. Stored record locations identify the earlier dates of moved or deleted records. Calculated
loads, six-minute slots and screen snapshots are invalidated when their inputs change.

`HealthConnectBudget` wraps remote reads in two continuously refilling buckets. Background work
leaves a reserve for foreground use. Quota pauses retain import progress; the app can continue from
saved data. Counters are operational metadata and survive Delete everything to avoid exhausting the
system quota by repeatedly resetting the app.

`SyncWorker` requests hourly work with WorkManager, subject to Android scheduling and battery
constraints. It requires background read permission and completed onboarding. It finishes pending
imports, refreshes recent changes and continues older history; it cannot guarantee an exact hourly run.

The optional `GoogleHealthHistory` import also saves records in Room. Pages read the saved copy;
they do not download Google Health data on navigation. Revoking access stops future reads and
retains the local copy. Profile → Delete everything removes local health data and returns to setup.

## Build and test

Use the Android SDK configured in `local.properties` and a compatible JDK:

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest
.\gradlew.bat assembleRelease
```

For device regression tests, run `tools/validate-device.ps1` with `-Adb` pointing to your SDK's adb
and, if needed, `-Serial` selecting a device. It builds the isolated `com.openfit.validation.debug`
package. Its synthetic import and deletion fixtures never run against your regular openFit package.

Debug uses `com.openfit.debug`, separate from release `com.openfit`. Release builds use R8 and
resource shrinking. A release without owner-provided signing credentials is unsigned; it is not
ready to upload. See [release prerequisites](docs/release-checklist.md).

## Project status

[TODO.md](TODO.md) tracks code work, device verification and owner decisions separately.
Calibration results in the [algorithm notes](docs/findings_algorithms.md) are historical comparisons,
not claims of clinical accuracy or newly rerun measurements.
