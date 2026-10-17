# Fixes from the four code reviews

Implemented and validated on 17 October 2026. This covers the confirmed findings from all four reviews, including transport robustness and the measured performance issues. No retained-memory leak had been established, so no speculative leak fix is claimed.

## Correctness and recovery

| Reviewed case | Implemented behavior |
| --- | --- |
| Resting-rate correction leaves later zone minutes stale | Invalidate later daily-load caches as well as the changed day; rescore with the corrected fallback measurement. |
| Partial Health Connect day hides distinct cloud readings | Merge observations rather than replacing the whole cloud day. Raw watch samples replace matching cloud minutes; separate times remain. Overlapping mirrored sessions prefer Health Connect. |
| Cloud discovery stops across long breaks | Search through empty chunks to the existing 15-year limit; version discovery metadata so an old truncated start date is searched again. |
| Birth dates before 1970 disappear | Store birth dates as ISO dates. Read recoverable legacy values and preserve valid older dates on subsequent saves. |
| Overlapping food/water entries are undercounted | Add independent entries; match equivalent cloud mirrors as a multiset instead of applying activity time-coverage deduplication. |
| Failed sync loses historical change events | Persist affected dates together with the advanced token, then acknowledge them only after the copy and rescoring succeed. Pending changes survive store recreation and are retained for temporarily ungranted types. |
| Historical deletions remain visible | Index the saved locations of record IDs and reread their original dates when deleted. |
| Moving a historical record leaves a duplicate | Invalidate its old dates and its new dates, using the saved ID location. |
| Duplicate exported sleep counts twice | Combine asleep intervals without counting overlapping instants twice; retain distinct naps. Apply the same duration calculation to Sleep and Today/history. |
| Awake-in-bed slots incorrectly count as asleep | Exclude that stage from Energy Bank's asleep flags. |
| Heart-rate interval spans midnight | Retrieve overlapping intervals, then clip individual samples to the requested day in Today, Heart, and load calculations. |
| Overnight edits leave wake-day slots stale | Track both old and new interval end dates, so the wake day's slots are invalidated too. |
| Today's Pulse Age remains stale | Recalculate on reload rather than reusing a value after backing measurements change. Do not reuse in-flight fetches predating a successful sync. |
| Journal outcomes ignore same-day corrections | Compare the outcome cache with an atomic backing-store revision when refreshing. |
| Health Metrics detail opens the original date | Pass the currently displayed overview date through the metric-row navigation callback. |
| Startup crashes when Health Connect is unavailable | Stop the follow-on sync work after a failed read; handle continuation errors while preserving coroutine cancellation. Report failed sync state. |
| Decimal-comma input saves the wrong magnitude | Preserve input text and parse decimal separators explicitly. Reject invalid input rather than silently removing characters. Initial goal values have no locale grouping separators. |
| Granting history access does not backfill saved empties | Detect expanded access, force the relevant import, reset earliest-record discovery, and reread older history before marking it complete. |
| DST fall-back drops the last hour | Map the fixed chart to local clock time. Repeated-hour samples share clock slots; nonexistent spring-forward slots stay empty. Update the slot-cache format. |
| Delete everything retains metadata/reimports immediately | Clear Google-history metadata and observable state, cancel/join app sync work, cancel scheduled work, and stop automatic imports until onboarding completes. |

## Resource use and performance

| Reviewed case | Change and validation |
| --- | --- |
| Blocked network requests ignore cancellation | Set a 15-second connection timeout and 30-second read timeout; disconnect the connection when cancelled. The controlled stalled-server request finished after cancellation without waiting for the peer to close. |
| Energy Bank blocks the main thread | Perform its data loading/calculations on a background dispatcher. The 45-day fixture's main-loop gap fell from the earlier 273 ms to under 20 ms in validated runs on this phone. This is a workload measurement, not a guarantee for all devices. |
| Daily totals scan all aggregates | Query the existing indexed key range. SQLite now reports an indexed SEARCH rather than a table SCAN. |
| Timestamp lookup encodes entire heart-rate series | Return the record timestamp directly. The same 500-lookups fixture allocated 0 bytes in place of approximately 11.9 MB. |
| Workout list rereads totals for every session | Load distance/calorie records once per displayed range and calculate each session's share from those records. The 21-session fixture used 6 reads instead of 86, with each session's distance and calories checked. |

## Existing installations

Room schema 3 adds an indexed record-location table through a non-destructive migration. Existing records are indexed in bounded batches off the UI thread. Cloud indexing is limited to intervals that can overlap later dates, avoiding a second index of every cloud minute total.

Import version 3 refreshes existing Health Connect copies and recomputes totals; daily-load, slot, and Today snapshot versions prevent reuse of the earlier incorrect calculations. The first sync after upgrading can do additional work. Cloud data and the user's profile/journal remain intact. A birth date already lost by the old zero/unset encoding cannot be reconstructed automatically and may need to be entered again.

## Validation

- Normal app: `gradlew.bat assembleDebug testDebugUnitTest --offline` passed, with **110 JVM tests** and no failures. Twelve new unit tests cover record merging, sleep overlap, decimal parsing, additive totals, and DST mapping.
- Isolated review APK: **25 targeted fix checks passed**, plus a lifecycle smoke check in which all three finished Activities became collectible.
- The device checks used production code, real Room/SharedPreferences, validated SDK records, controlled Health Connect responses, and a local/in-process HTTP fixture. The two UI checks exercised the actual goal dialog and Health Metrics navigation. Live Health Connect data and the real Google account were not changed.
- The first device run upgraded the review app's existing schema-2 database successfully. The ordinary app installation was not upgraded or replaced.
- Android lint was attempted but could not resolve uncached offline tooling: `intellij-core-32.4.1.jar` and `kotlin-compiler-32.4.1.jar`. No lint-pass claim is made.
- `git diff --check` passed. The temporary `com.openfit.reviewcheck.debug` APK was uninstalled, and `com.openfit.debug` remained installed.

Local validation artifacts are under the ignored `build/review-validation` directory: `fixes-results.json` combines the successful cases from `fixes-core-results.json`, `fixes-goal-results.json`, and the navigation result in `fixes-ui-first-results.json`. The initial UI attempts blocked by the lock screen/accessibility setup are retained separately. The runner is in `src-fixes`, with `fixes.init.gradle`; it refuses to run against the normal app package.

The normal debug APK is at `app/build/outputs/apk/debug/app-debug.apk`. It was built but not installed over the user's regular app.
