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

- **Base URL:** `https://health.googleapis.com/v4`. Endpoints used: `dailyRollUp` (over a date range),
  `rollUp`, `list` and `reconcile`, each with page tokens. At most 10 pages per request
  (`MAX_PAGES`).
- **Data family:** `dataSourceFamily = google-wearables` restricts results to Fitbit and Pixel
  devices.
- **AZM:** rollups return *points*, so cardio and peak are halved to get minutes.
- **Parsing helpers** (`GoogleHealthParsing.kt`): `parseHourly`, `parseHeartRateRange`,
  `parseDailyRollups`, `dailyValues`, `sleepMinutesByEndDate`.
- **Sign-in:** Identity `AuthorizationClient` (`GoogleHealthAuth`). **Not working yet.** It needs an
  OAuth client in Google Cloud: Android type, package `com.openfit`, the debug and release SHA-1,
  and the Health API scopes on the consent screen. Status and Connect/Disconnect are in the
  Profile dialog.
- **Why it matters:** this is the only way to get Fitbit's *own* AZM, and possibly its
  Readiness and Sleep Score, instead of recomputing them.

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
