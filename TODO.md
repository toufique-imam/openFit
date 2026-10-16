# openFit — work and tests left

Status as of 16 Oct 2026 (commit `9acc51d`). Items are grouped by area and roughly ordered by priority within each group.

## 1. Before a Play Store release

### Licensing and names
- [ ] Rewrite the strings copied word for word from Fitbit 4.67 (about 33, mostly Cardio Load and Readiness explanations).
- [ ] Rename Fitbit's product names: Daily Readiness, Active Zone Minutes, Cardio Load.
- [ ] Rename Pulse's product names: Pulse Age, Energy Bank.
- [ ] Reword Customize Today's preset names, which come from Fitbit:
  - Build cardio fitness, Get more active, Sleep better, Manage weight, Manage stress, Improve heart health.
  - Also the "Set your focus" and "Show/hide other metrics" headings.
- [ ] Decide how close to Fitbit's look the app may stay (colours, icons, layouts). Give openFit its own identity where needed.
- [ ] Remove `docs/findings_jadx.md`, and the other docs' references to the decompiled Fitbit app, from the repo and its history.
- [ ] Remove "Pulse" mentions from code comments and from user-facing text:
  - `EnergyBank.kt`, `Fitness.kt`, `PulseAge.kt`, `PulseAgeSource.kt`, `HealthSync.kt`, `StressScreen.kt`, `TodayViewModel.kt`, and the age, onboarding and profile screens.
- [ ] Confirm nothing derives from pulse-mobile's code (PolyForm Noncommercial; its engine re-implements a decompiled app). Only ideas and published formulas are allowed.
- [ ] Stress sits in Fitbit's "Stress Management Score" spot but means the opposite (higher is worse). Rename it, or explain the difference on the page.

### Play Console and Google
- [ ] Privacy policy (in-app link and store listing). Points to cover:
  - Data stays on the phone; no account.
  - Google Health API sign-in is optional.
  - Delete everything removes it all.
- [ ] Health Connect permissions declaration: why each data type is read, plus the history and background permissions.
- [ ] Google Health API: OAuth consent screen verification for the restricted health scopes. Or drop the Google Health source before release.
- [ ] Final `applicationId` (it can't change after publishing). Add `applicationIdSuffix ".debug"` to debug builds, so testing doesn't use up the release app's Health Connect quota.
- [ ] Release build:
  - Signing.
  - `isMinifyEnabled = true`, with R8 rules for kotlinx.serialization, Room and Health Connect.
  - Version code and name.
- [ ] Launcher icon and store graphics.
- [ ] Target the latest SDK the Play Store requires, and check behaviour on Android 14+ (Health Connect built into the system) as well as Android 13 (Health Connect as an app).

## 2. Data, sync and quota

- [ ] **Watch the first full import finish on the test phone** once Health Connect's daily quota recovers:
  - Note the total reads it took (expected roughly 200–300).
  - Note how far back the data goes.
  - Note how long it took.
- [ ] Confirm the hourly `SyncWorker` runs. Check whether the background read permission is granted on the phone, and what the worker reads.
- [ ] Recent sync on app open and pull-to-refresh: check that a watch syncing late shows up, and that cardio load and Energy Bank for those days are worked out again.
- [ ] Changes feed: a deletion in Health Connect currently re-reads the last 7 days of every type. Check that this is enough, or track record IDs to remove exactly the deleted record.
- [ ] Time-zone change: days are filed by the zone at read time. Decide whether to re-file or re-read when the zone changes.
- [ ] Move `DayLoadCache` and `SlotCache` from SharedPreferences into Room. The slots are about 1.5 KB a day and SharedPreferences loads the whole file into memory.
- [ ] Prune the quota tracker's per-day counters (`hc_budget`), which grow by a few keys a day.
- [ ] `HealthStore.locks` keeps one mutex per workout ever read. Bound it, or drop a key once its read finishes.
- [ ] Decide whether the per-screen snapshots (`SnapshotStore`) are still needed now that pages read the local copy. They may only add a second cache to keep in step.
- [ ] Google Health API source: route it through the quota/offline design too, or remove it (see release section).
- [ ] When Health Connect is unavailable or permissions are revoked, check what the saved copy shows and when it is cleared.

## 3. Features and UI

- [ ] Accessibility: the period arrows say "Previous day"/"Next day" on Week, Month and Year views. Name the period.
- [ ] Section tabs show every card in the section, even ones hidden on Today. Confirm that's the wanted behaviour.
- [ ] Custom focus picker: check the scrolling and the "Off" row; let a slot be moved, not only cleared.
- [ ] Profile: decide whether to add Pulse's photo and height rows (skipped so far).
- [ ] Sync card: show which data types are copied and how far back each goes.
- [ ] Onboarding paths still untested:
  - Health Connect not installed or needing an update.
  - Permissions denied.
  - "Not now".
  - The quota pause with "Continue in the background".
- [ ] Delete everything: run it end to end. Check that it stops a running sync, clears Room and preferences, keeps the quota tracker, and returns to onboarding.
- [ ] Remaining Fitbit-parity pages and features, per [`docs/findings_fitbit_ui.md`](docs/findings_fitbit_ui.md).
- [ ] Features chosen from Pulse, not built yet:
  - Sleep Planner.
  - Strain (overlaps Cardio Load: decide first).
  - Breathe session.
  - HRV in Stress.
- [ ] Light theme (the app is dark only).

## 4. Algorithms to re-check

- [ ] Cardio load: moving minutes now come from saved per-minute steps (minutes with steps only). Re-compare day values with Fitbit for the same 37 days as before (within ~5 on most days).
- [ ] Cardio target ranges: re-run the comparison (17/19 days within 2).
- [ ] Readiness: 9.4 points RMS against Fitbit; a refit earlier did not help. Revisit with more nights.
- [ ] Sleep Score: 3.2–3.3 points RMS over 31 nights. Keep checking new nights.
- [ ] Energy Bank (7.6 points RMS against Pulse) and Stress (day average within 3.7): re-check after the store changes. Days that could not be read now count as not worn.
- [ ] VO2 max: compare with a measured value if a watch writes one to Health Connect.
- [ ] Pulse Age: review the half weight given to estimated factors, and the name (see release section).

## 5. Automated tests to add

The JVM tests currently cover only `domain/` (15 files) and Google Health parsing.

- [ ] `HealthConnectBudget`:
  - Refill rate.
  - Background reserve.
  - Refusal before the limit.
  - Dropping to empty on Health Connect's error.
  - `waitFor()`.
  - Needs prefs injected, or Robolectric.
- [ ] `RecordCodec`: round trip for every saved record type (heart rate samples, sleep stages, skin temperature deltas, and so on).
- [ ] `HealthStore`, with an in-memory Room database and a fake Health Connect client:
  - Day runs and the heart-rate chunking.
  - Filling a never-read day once.
  - `force` re-reads.
  - Partial results when Health Connect refuses.
  - Days before `firstDay` cost no call.
  - Daily, hourly, per-minute and per-workout totals.
- [ ] `HealthSync`:
  - Import order and resume after the quota pause.
  - Single flight (a second Sync joins; a full-priority import takes over a background one).
  - Recent sync re-reads three days plus changed days, and forgets their cached scores.
- [ ] Snapshot serialization: round trip for `TodaySnapshot` and each detail page's `Ready` state. An old row should still decode.
- [ ] Room migration 1 → 2 keeps saved screens (`MigrationTestHelper`).
- [ ] `TodayLayoutStore`: save and load, unknown names from an older version, and the three-tab limit.
- [ ] Profile: birth date to age, and a typed age replacing the birth date.
- [ ] UI tests (Compose):
  - Customize Today (preset, custom focus, move section, hide card, add tab, Save versus Close).
  - Onboarding flow.
  - Back-stack scroll and tab restore.
  - A detail page keeping its period after a failed load.

## 6. Manual checks on the test phone

- [ ] Cold start opens from the saved copy with no Health Connect reads beyond the recent sync.
- [ ] Changing dates and opening detail pages makes no Health Connect reads once the import is done. Check "Answered from saved data" on the profile page.
- [ ] Pull-to-refresh on Today reads the last three days and updates the cards.
- [ ] Year views (Steps, Distance, Energy burned, Active Zone Min, Heart, Sleep, Weight, VO2 max) fill from the copy after the import.
- [ ] Toasts appear once (not repeated) when saved data stands in.
- [ ] Customize Today and the bottom-bar tabs survive an app restart.
- [ ] Rotation and process death: back stack, scroll positions and the Customize draft.

## 7. Docs

- [ ] README: describe the data flow:
  - Health Connect → `HealthSync` → Room copy (`HealthStore`) → pages.
  - The quota budget.
  - Onboarding import, recent sync, background work.
- [ ] Update `docs/findings_algorithms.md` with the re-checked numbers from section 4.
