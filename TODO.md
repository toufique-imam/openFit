# openFit — work and tests left

Status as of 17 Oct 2026. Items are grouped by area and roughly ordered by priority within each group.

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
- [ ] Final `applicationId` (it can't change after publishing). Debug builds already install beside it (`applicationIdSuffix ".debug"`), with their own Health Connect quota.
- [ ] Release build:
  - Signing.
  - `isMinifyEnabled = true`, with R8 rules for kotlinx.serialization, Room and Health Connect.
  - Version code and name.
- [ ] Launcher icon and store graphics.
- [ ] Target the latest SDK the Play Store requires, and check behaviour on Android 14+ (Health Connect built into the system) as well as Android 13 (Health Connect as an app).

## 2. Data, sync and quota

- [x] First full import on the test phone (raw records, 17 Oct): 28 calls in about 7 seconds; the phone's data starts 8 Sep. Recent sync: 19 calls.
- [ ] Older history (before the 180-day window) on a phone that has some: check it copies in the background, resumes after the quota pause, and that year views fill.
- [ ] A type granted after onboarding: check the next app open imports its 180 days and older history (no other type is read again).
- [ ] Health Connect wiped (on Android 13 it is an app; reinstalling it deletes all its data, as happened on the test phone on 6 Oct 2026): check openFit keeps its copy, re-reads only the last days, and copies what reconnected apps write back (the changes feed reports days before the copy's first day; they are read back to the 2-year limit).
- [ ] Upgrade from a copy made before raw records (import version 1): check it re-imports only the amounts' records and adds the totals up again.
- [ ] Merging apps' amounts: the app that writes heart rate ranks first. Check a phone with no wearable, with two (a watch and a ring), and a wearable changed mid-history.
- [ ] Health Connect fills unrecorded time with resting calories in its energy totals; openFit counts only what was recorded. Decide whether to estimate it (it shows only on a day the watch's data starts or stops).
- [ ] Storage: the raw step, distance and calorie records add about 85 KB a day (heart rate about 200 KB). Store them more compactly (columns instead of JSON objects) before two years of history make the database large.
- [ ] Confirm the hourly `SyncWorker` runs. Check whether the background read permission is granted on the phone, and what the worker reads.
- [ ] Recent sync on app open and pull-to-refresh: check that a watch syncing late shows up, and that cardio load and Energy Bank for those days are worked out again.
- [ ] Changes feed: a deletion in Health Connect currently re-reads the last 7 days of every type. Check that this is enough, or track record IDs to remove exactly the deleted record.
- [ ] Time-zone change: days are filed by the zone at read time. Decide whether to re-file or re-read when the zone changes.
- [ ] Move `DayLoadCache` and `SlotCache` from SharedPreferences into Room. The slots are about 1.5 KB a day and SharedPreferences loads the whole file into memory.
- [ ] Prune the quota tracker's per-day counters (`hc_budget`), which grow by a few keys a day.
- [ ] Decide whether the per-screen snapshots (`SnapshotStore`) are still needed now that pages read the local copy. They may only add a second cache to keep in step.
- [x] Google Health history copy: works (17 Oct 2026). 14 months in 173 calls; steps, distance and heart rate match Health Connect's.
- [ ] Google Health: duplicates in workouts and logs read from all sources (a workout recorded by the watch and logged in another app).
- [ ] When Health Connect is unavailable or permissions are revoked, check what the saved copy shows and when it is cleared.

## 3. Features and UI

- [ ] Today's focus rings: Exercise days, Sleep and Steps now match Fitbit (default goals, tick when met, empty track without data). Compare the other presets' rings and the big single-metric ring the same way.
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

- [ ] Cardio load: moving minutes now come from per-minute steps added up from raw records (identical to the earlier copy on 179 days). Re-compare day values with Fitbit for the same 37 days as before (within ~5 on most days).
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
- [x] Merging apps' amounts and sharing them out by day, hour and minute (`TotalsTest`).
- [x] `RecordCodec`: heart rate, sleep, weight and the amounts (`RecordCodecTest`).
- [ ] `RecordCodec`: the remaining types (skin temperature deltas, VO2 max, mindfulness, and so on).
- [ ] `HealthStore`, with an in-memory Room database and a fake Health Connect client:
  - Pages read newest first over a run, each day saved once the pages have passed it.
  - Filling a never-read day once.
  - `force` re-reads.
  - Partial results when Health Connect refuses.
  - Days before `firstDay` cost no call.
  - Daily, hourly, per-minute and per-workout totals from saved records, including the day after an interval that runs past midnight.
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
