# Data sources

openFit reads from two sources and merges them:

- **Health Connect**, always.
- **Google Health API**, optional, once signed in.

Both implement `health/DailyMetricsSource` (`load`, `exerciseDays`, `history`).

## Health Connect (`health/HealthConnectSource.kt`, `HealthConnectGateway.kt`)

- **Library:** `androidx.health.connect:connect-client` 1.1.0.
- **Permissions:**
  - Read permissions for every displayed type.
  - Optional permissions (`OPTIONAL_PERMISSIONS`): history (older than 30 days) and background
    read. They are only requested when the platform reports the feature as available.
- **Paging:** `readRecords` returns a `pageToken`. Loop while `!pageToken.isNullOrEmpty()`.
  - *Bug found:* looping on `!= null` never ended, because the last page returns an empty string.
  - That caused "Request rejected. Rate limited" and made the permission screen close itself.
- **Rate limits are aggressive.**
  - The ViewModel reloads on resume only if the data is older than 1 minute (`refreshIfStale`).
  - History backfill is capped at 7 days per load (`MAX_BACKFILL_DAYS`).
- **What Fitbit writes to Health Connect** (from pulse-mobile's notes and observation):
  - Writes: steps, heart rate, sleep, resting HR, and HRV every 5 minutes overnight.
  - Doesn't write: AZM, SpO2 or food. openFit computes AZM itself.
  - The phone also writes steps. Consider filtering to the Fitbit data origin to avoid
    double-counting.
- **Per-day load:**
  - `dayLoad()` computes AZM and Cardio Load for a past day from raw heart rate plus "moving
    minutes".
  - Moving minutes are 1-minute step aggregates plus exercise sessions.
  - Results are cached in `DayLoadCache`: SharedPreferences `day_load`, value `"zone;cl"`.
  - The cache key includes `age:sex`, so a profile change invalidates it.
  - Only days before yesterday are cached, so late syncs still land.

## Google Health API (`googlehealth/`)

Used only to copy Fitbit's own history into the local copy (`GoogleHealthHistory`); pages never call it.

- **Why:** Health Connect holds only what was written to it on this phone. On the test phone it was reinstalled on
  6 Oct 2026, which deleted two years of data; Fitbit's servers still had every day from 7 Aug 2025.
- **Sign-in:** Identity `AuthorizationClient` (`GoogleHealthAuth`), four read-only scopes (activity and fitness,
  health metrics, sleep, nutrition). Needs an OAuth client in Google Cloud: Android type, the app's package
  (`com.openfit.debug` for debug builds) and signing SHA-1, the scopes on the consent screen, and the account as a test
  user while the app is in Testing. Working since 17 Oct 2026.
- **Measured limits (17 Oct 2026):**
  - 300 requests a minute per user; each call takes 0.5–4 s, which sets the pace.
  - `rollUp` returns up to 10,000 windows a call when asked (`pageSize`, default 1,440), leaves out empty windows,
    and pages with a `pageToken` in the body. Heart rate and total calories allow 14 days a call, steps, distance and
    floors 90. A `fields` mask on the average alone halves heart rate's bytes.
  - `list` stops at about 1,760 points a page whatever `pageSize` asks; sleep and exercise at 25.
  - The heart-rate rollUp's average is `beatsPerMinuteAvg`. Values can be `"NaN"` (skin temperature).
  - VO2 max is empty in all three types (`vo2-max`, `run-vo2-max`, `daily-vo2-max`) for a Charge 6 user.
- **The copy:** each type newest first in chunks within those limits; minute totals (steps, distance) by rollUp, not
  list (8 calls a year, not 120). 14 months took 173 calls and 8 minutes. Heart rate is kept as one-minute averages:
  identical, minute by minute, to Health Connect's readings averaged the same way, which is what cardio load and zone
  minutes use. Steps and distance match Health Connect's to the step.
- **Storage:** beside Health Connect's rows (`cloud:<type>`), never over them. Per day, the amounts take both and
  count each moment once; other types take Health Connect's records when it has any, else the cloud's.
- **Top-up:** on opening the app (at most every 10 minutes) and pulling to refresh (every 30 s), the last 3 days of each
  type: 14 calls, as types the copy has never held (floors, body fat, VO2 max here) are asked about only weekly.
- **Network:** an app started in the background (a broadcast) cannot reach the API on Samsung; WorkManager jobs need a
  network constraint.

## Merging (`domain/MergeMetrics.kt`, `domain/DailyHistory.kt`)

- **Today's metrics:**
  - Most scalar fields take the primary source, falling back to the secondary.
  - Hourly steps take the max per hour.
  - Heart-rate range takes the widest span.
  - Latest heart rate takes the newest reading.
  - Cardio Load takes primary ?: secondary.
- **History:** `mergeHistory` gives earlier sources priority per day.
- Sources load in parallel. History covers 4 weeks before the current week
  (`TodayViewModel.HISTORY_WEEKS`).

## Open questions

- Should Google Health be the primary source for AZM once sign-in works? It matches Fitbit
  exactly, while Health Connect gives a recomputed estimate.
- Use the Health Connect data-origin filter (Fitbit only) for steps and heart rate?
