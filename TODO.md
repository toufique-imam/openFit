# openFit — work and tests left

Status as of 17 Oct 2026. Completed implementation and controlled regression checks are marked below;
checks requiring real reference data, accounts, release credentials or additional hardware remain open.
See [decisions](docs/product-decisions.md), [validation](docs/todo-progress.md) and
[release prerequisites](docs/release-checklist.md).

The existing first-import window is **365 days**, followed by older history up to two years. The original
180-day wording in this checklist was stale; onboarding and docs now match the code.

## 1. Before a Play Store release

### Licensing and names

- [x] Rewrite the copied training-load and recovery explanations and training-status messages.
- [x] Rename Daily Readiness → Recovery score, Active Zone Minutes → Effort minutes, Cardio Load → Training load.
- [x] Rename Pulse Age → Health age and Energy Bank → Vitality reserve. Keep internal saved names compatible.
- [x] Rewrite Customize Today's presets and headings.
- [x] Establish openFit's identity: navy/blue/teal/apricot palettes, system typography and original rising-bars mark.
- [x] Remove `docs/findings_jadx.md` and retained documents' references to the decompiled app from the working tree.
- [ ] Remove that document from Git history. **Deferred by owner.** No history rewrite or force push has been performed.
- [x] Remove Pulse branding from user-facing text and comment prose in the listed implementation files.
- [ ] Confirm independent source provenance against pulse-mobile and independently justify the thresholds disclosed in `CardioTargets.kt`. See [model provenance](docs/model-provenance.md).
- [x] Rename Stress → Body stress; explain that higher means more physiological stress and that this is not an emotional assessment.

### Play Console and Google

- [x] Add the complete in-app privacy policy, accessible from onboarding and Profile, covering local storage, optional Google access, revocation and deletion.
- [ ] Publish/link the public policy and finish the store listing. **Deferred by owner:** publisher name, privacy contact and policy URL.
- [x] Draft the Health Connect declaration with the purpose of each read type, history and background access.
- [ ] Submit and obtain approval for the declaration in Play Console.
- [ ] Complete Google Health OAuth verification. **Owner decision:** retain the optional source while verification is pending.
- [ ] Confirm the final `com.openfit` application ID before publishing. Ordinary debug stays `com.openfit.debug`.
- [x] Enable release R8/resource shrinking, optimized defaults and dependency consumer rules; build an unsigned optimized APK.
- [x] Add environment-based signing configuration without committing credentials.
- [ ] Supply the release key, confirm version code/name (currently 1 / 0.1.0), and smoke-test the signed optimized build.
- [x] Create launcher icon and original 512px icon / 1024×500 feature artwork in [docs/store](docs/store/README.md).
- [ ] Capture final store screenshots and complete publication.
- [x] Target API 36, the current Play requirement; fix API 26 compatibility reported by lint.
- [x] Run controlled validation on Android 13 and an API 36 emulator.
- [ ] Test actual system Health Connect grants/imports on Android 14+ hardware. Emulator fake-client checks do not establish permission/provider behavior.

## 2. Data, sync and quota

- [x] First full import on the test phone (raw records, 17 Oct): 28 calls in about 7 seconds; data starts 8 Sep. Recent sync: 19 calls. Earlier recorded result.
- [x] Test older history beyond the initial window, partial-copy resume, version-1 upgrade, new grants and single-flight imports with a fake provider and real Room.
- [ ] Repeat older-history/background quota-resume and year-view checks on a phone with more than one year of Health Connect data.
- [x] Import newly granted types/history on app open. Tests ensure existing historical ranges are not copied again; normal recent/fresh-token fallback reads may still run.
- [x] Test provider wipe and permission revocation retaining older local records; reads stop after revocation. Revocation alone does not clear the copy. Today asks to reconnect when provider access is missing; detail sources filter by current grants.
- [ ] Verify wipe/reconnect behavior on a real provider, including backfilled history and expired changes tokens.
- [x] Test upgrade from import version 1. Current version 3 intentionally refreshes all affected raw types to repair earlier copy/changes defects, then rebuilds totals; completed days are skipped after interruption.
- [ ] Check actual no-wearable, watch-plus-ring and changed-wearable histories. JVM tests cover overlap/priority arithmetic, not each producer's behavior.
- [x] Keep energy based on recorded intervals and explain missing unrecorded/resting gaps on the page.
- [x] Compress larger raw-day payloads losslessly into GZIP BLOBs; keep legacy JSON readable and compact old rows in bounded resumable batches. The dense synthetic fixture shrinks by at least 4×.
- [ ] Measure database size/performance with two years of a real user's dense records.
- [ ] Confirm the ordinary app's hourly SyncWorker execution and background grant on the phone. Scheduling and required permission are documented; an exact hourly run is not guaranteed by Android.
- [x] Test recent sync's three-day reread plus older changed days, rebuilt totals and derived-cache invalidation.
- [x] Track record IDs and original interval dates for old/moved/deleted records. Persist pending changes until copied successfully; use a recent fallback for unknown deleted IDs.
- [x] Re-file saved records on time-zone changes, rebuild local totals/caches and recreate screen models. Test UTC→Los Angeles date/hour movement without remote reads.
- [x] Move DayLoadCache and SlotCache to Room; rebuild old preference caches locally and reject stale writers.
- [x] Prune quota per-day counters while preserving recent operational history and throttling state.
- [x] Retain screen snapshots for immediate rendering and expensive derived states; document the decision and cover all serialized states.
- [x] Google Health history copy: earlier phone result, 14 months / 173 calls; steps, distance and heart rate matched Health Connect.
- [x] Use Google reconciliation for workouts, food and water; recopy affected historical chunks after upgrading. Test request paths, paging and transport cleanup.
- [ ] Confirm reconciliation against a real account containing duplicate workouts/logs after OAuth verification.

## 3. Features and UI

- [ ] Fresh visual comparison of the other focus presets and large single-metric ring, including empty/met/over-goal states. Existing exercise/sleep/steps behavior is retained.
- [x] Label period arrows correctly for Day, Week, Month, Three months and Year.
- [x] Keep section tabs as full section views, including cards hidden only on Today; document the scope.
- [x] Make custom focus scrollable with an always-available Off row and accessible move-up/down controls. Preserve its draft across recreation.
- [x] Omit unused profile photo/height rows for now; record the decision.
- [x] Show copied types, date coverage and day counts in the Sync card.
- [x] Add onboarding regressions for unavailable/update-required provider, denied permissions, Not now, and quota pause / Continue in the background.
- [x] Run Delete everything end to end in the isolated package: stop import/work, clear Room/profile/goals/layout/journal/cloud metadata, retain quota and return to onboarding.
- [x] Reconcile historical UI gaps with current implementation and decisions in [dashboard notes](docs/findings_fitbit_ui.md). Keep own icons/header and capped goal rings; omit a health-record quick-add FAB while read-only.
- [x] Add Sleep Planner with wake time, time in bed, wind-down, saved goals and DST-aware calculations.
- [x] Use Training load for strain rather than adding a duplicate score.
- [x] Add a timed breathing session with start/pause/reset and accessible phase announcements.
- [x] Show HRV as separate Body stress context; defer unvalidated score weighting.
- [x] Add system light/dark themes, including charts, sheets and navigation, with automated text-contrast checks.

## 4. Algorithms to re-check

Historical measurements remain historical; no new paired reference records were supplied.

- [ ] Recompare training load for the original 37 paired days and additional days after storage changes.
- [ ] Rerun target-range comparison (previously 17/19 within 2).
- [ ] Recovery score: revisit with more nights (previous RMS 9.4).
- [ ] Sleep score: keep checking new nights (previous RMS 3.2–3.3 across 31 nights).
- [ ] Vitality reserve and Body stress: rerun paired comparisons (previous RMS 7.6 and day-average difference 3.7).
- [ ] Compare estimated VO2 max with a measured value written by a watch.
- [x] Review health-age naming and estimated-factor half weight: retain as an explicit uncertainty heuristic, not a clinically established conversion.
- [ ] Independently validate the health-age input curves, joint scaling and estimated-factor sensitivity.

## 5. Automated tests

- [x] HealthConnectBudget: refill, reserve, pre-limit refusal, provider quota error, waitFor and counter pruning using injected preferences/clock.
- [x] Amount merging and day/hour/minute/workout distribution, including midnight (`TotalsTest` and device tests).
- [x] RecordCodec round trips all 18 supported types, including amounts, skin temperature, VO2 max and mindfulness.
- [x] HealthStore: newest-first paging, partial saves/resume, fill-once/force, refresh cutoff, local-only reads, midnight totals, ID-based old deletions and timezone refiling.
- [x] HealthSync: import order/resume, priority takeover/single flight, version-1 upgrade, new grants, recent+changed days and score invalidation.
- [x] Snapshot round trips for Today and every detail Ready state, plus a legacy row.
- [x] Room migration 1→5 preserves saved screens and validates the migrated schema (real SQLite/Room fixture).
- [x] TodayLayoutStore: persistence, unknown names, duplicates, three-tab limit, reordering and saved drafts.
- [x] Profile: birth date/age, birthdays, legacy encoding and typed age replacement.
- [x] Compose UI through Android accessibility: presets, custom focus, move section, hide card, add tabs, Save/Close and draft recreation.
- [x] Onboarding UI paths listed above.
- [x] Back-stack scroll/tab recreation restore and a detail page retaining its selected period after a failed load.
- [x] Sleep Planner DST calculations, breathing controls and both-theme contrast.

## 6. Manual checks on the ordinary app

These remain distinct from synthetic fixtures; preserve the real saved health copy.

- [ ] Cold start opens from saved data with only expected recent-sync reads.
- [ ] Date navigation/detail pages use no remote reads after import; check “Answered from saved data”. Controlled saved-store/navigation fixtures cover this automatically.
- [ ] Real late-watch data appears after pull-to-refresh and cards update.
- [ ] All year views fill correctly: Steps, Distance, Energy burned, Effort minutes, Heart, Sleep, Weight and VO2 max.
- [ ] Saved-data toasts appear once under actual provider failure/quota conditions.
- [ ] Customize layout and bottom tabs survive a full ordinary-app restart. Store persistence and recreation are automated.
- [ ] Rotation and actual process death preserve back stack, scroll and Customize draft. Activity recreation is automated; process death is not equivalent.

## 7. Docs

- [x] Root README covers Health Connect → HealthSync → Room → pages, quota, first import, recent refresh, background work and builds/tests.
- [x] Record product decisions, release prerequisites, provenance limitations and reproducible validation.
- [ ] Update [algorithm notes](docs/findings_algorithms.md) with fresh paired measurements once section 4 is run.
