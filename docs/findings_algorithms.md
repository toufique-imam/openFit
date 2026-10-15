# Algorithms

Fitbit computes Active Zone Minutes, Readiness, Sleep Score and the Cardio Load target on its
servers. The 4.67 APK only fetches and displays them (see [findings_jadx.md](findings_jadx.md)), so
openFit computes its own versions on the device. All of them live in `app/src/main/java/com/openfit/domain/`
and are pure functions with unit tests in `app/src/test/java/com/openfit/domain/`.

## Heart-rate basics

- `maxHeartRate(age) = 220 − age` (`CardioLoad.kt`).
- Heart-rate reserve (HRR) fraction = (bpm − resting) / (max − resting).
- Samples are averaged per minute (`averageBpmByMinute` in `ZoneMinutes.kt`) before any scoring.

## Active Zone Minutes (`ZoneMinutes.kt`)

| Zone | HRR | Credit per minute |
| --- | --- | --- |
| Fat burn | ≥ 40 % | 1 |
| Cardio | ≥ 60 % | 2 |
| Peak | ≥ 85 % | 2 |

- The Google Health API daily rollup reports AZM as *points*, so cardio and peak values are halved
  when parsing (`GoogleHealthParsing.parseZoneMinutes`).
- **Known gap:** openFit's AZM from Health Connect heart rate reads higher than Fitbit's (365 vs
  Fitbit's own figure for the same week). Likely causes:
  - Fitbit's server applies a movement or wear filter.
  - Fitbit uses a different resting HR.
  - Fitbit adds a minimum-duration rule.

  Not resolved yet.

## Cardio Load (`CardioLoad.kt`)

Source: arXiv 2508.11613 (Google's published Cardio Load model, a TRIMP variant).

- Per minute, x = HRR fraction (capped at 1):
  - x < 0.30 → 0
  - otherwise load = 0.64 · x · e^(k·x), with k = 1.92 (male), 1.67 (female), and their mean when
    sex is unset.
- 0.30 ≤ x < 0.40 is weighted × 0.5. **This is our choice.** The paper doesn't give a factor for
  that band.
- **Movement gate:** a minute only counts if it has steps or falls inside an exercise session
  (`HealthConnectSource.movingMinutes`). This removes stress and caffeine spikes while sitting.
- Daily values for past days are cached in `DayLoadCache` (see data sources).

## Daily Readiness (`Readiness.kt`)

Fitbit's readiness formula is server-side and unpublished. Ours is a z-score blend against the
user's own 30-day baseline:

| Component | Weight | Signal |
| --- | --- | --- |
| HRV | 0.4 | ln(HRV); half today's z, half the 7-day mean's z |
| Resting HR | 0.3 | negative z (lower is better) |
| Sleep | 0.3 | Weighted last 4 nights (0.4/0.3/0.2/0.1) vs baseline; −1 z penalty under 6 h |

- Each z is clamped to ±3. Minimum standard deviations are ln-HRV 0.05, resting HR 2 bpm and sleep
  0.5 h, so a very flat baseline doesn't explode the score.
- Confidence ramps from 0 to 1 as the baseline goes from 4 to 14 days.
- Score = 50 + 20 · Σ(w·conf·z) / Σw, clamped to 0–100.
- Bands: Low ≤ 29, High ≥ 65, otherwise Moderate.
- **No score** unless last night's sleep exists and at least two components are available. Fitbit
  also shows "No score" until sleep syncs. Without this rule, a 2 am resting-HR-only score read
  "100 · High".
- **Illness flag** (any of):
  - resting HR ≥ baseline + 7 bpm today
  - ≥ baseline + 4 bpm for 3 days running
  - resting z ≤ −2.5 together with HRV z ≤ −2.0
- **Calibration data point:** Fitbit showed 48 · Moderate on 25 Sept 2026 (screenshot in `ss/Today`).

## Cardio Load target range (`CardioTargets.kt`)

Fitbit 4.67 gives a **daily target range** ("Target 11–46"). Its strings say the range is "personalized based on your recent cardio load and your readiness", and that the fitness target (maintaining / improving) sets "how challenging your daily target load will be". The 2025 white paper (`docs/2508.11613v2.pdf`) describes the *newer* weekly target, not this one, so the daily model was reverse-engineered from the screenshots in `ss/Cardio Load` (week of 20–26 Sept 2026, readiness 48, goal "maintaining"):

| Day | Load | Fitbit range | implied low / high ÷ chronic |
| --- | --- | --- | --- |
| Sun 20 | 18 | 57–89 | 1.03 / 1.14 |
| Mon 21 | 103 | 66–96 | 1.01 / 1.11 |
| Tue 22 | 49 | 4–37 | 1.04 / 1.14 |
| Wed 23 | 14 | ~4–28 | – / 1.12 |
| Thu 24 | 77 | 58–91 | 1.05 / 1.15 |
| Fri 25 | 6 | 11–46 | 1.05 / 1.16 |

The bounds move day to day by exactly the load that enters or leaves a rolling 7-day window, so the range is "today's load that brings the last 7 days to a window around the chronic weekly load":

- **chronic** = 28-day rolling mean of weekly load before today (four rolling 7-day blocks, each needing ≥ 4 tracked days). A daily EWMA or max(RM, EWMA) fits no better; RM is used.
- **range** = [low, high] × chronic − (load of the previous 6 days), with:
  - Maintain: 1.04–1.14; Improve: 1.14–1.24 (assumed +0.10, no Fitbit data yet).
  - Readiness Low (≤ 29): window −0.10; High (≥ 65): +0.05 (assumed; the moderate days above fit unshifted).
  - Illness flag: 0 – 0.05 × chronic. Overreached weeks: low = 0, high ≥ 0.05 × chronic.
- **No target** without today's readiness or a week of history (Fitbit shows "0 · No target" + "No score" together).
- **Ring and Day-chart scale** = 0.45 × chronic (Fitbit's day-chart axis of 139 = 0.45 × 310, and Fri's band ends at 0.32 of the arc = 46 / 144).
- The fit leaves ±0.02 × chronic (≈ ±7 load) unexplained (readiness and chart-reading error). `CardioTargetsTest` reproduces Fitbit's 24/25 Sept ranges from its own loads within 8.

### Training status ("Your past seven days of training")

Fitbit: "Every day, Fitbit looks at how much cardio load you've earned the past 7 days and compares it to the past 28 days." openFit: acute = last 7 days (≥ 4 tracked, scaled), chronic = mean of the 28-day blocks (needs ≥ 2). Thresholds (assumed, standard ACWR bands): < 0.6 undertraining, < 0.8 at risk of undertraining, < 1.1 maintaining, < 1.3 improving, < 1.5 at risk of overtraining, else overtraining. Fitbit's bar has six equal segments (yellow, mint, mint, teal, teal, yellow); the marker is placed linearly within its segment.

### Week / Month / Year views (`CardioPeriods.kt`)

- Points sit at x = 24 + i × 288 / n dp; each has its own range box (one slot wide).
- Axis top = 1.2 × the highest value or band; middle label = top / 2 rounded **up** to a multiple of 5 (Fitbit: 123/65, 106/55, 139/70).
- Month list = the Sunday weeks touching the month, newest first, as daily averages ("27 Sept – 3 Oct").
- Month headline = the month's average day vs the 28 days before it: < 0.8 undertrained, < 1.15 maintained, < 1.3 improved, else overtrained. Fitbit called September (44/day after August's 40, ratio 1.10) "Maintained fitness", so the improve threshold is above 1.10.
- Year points = monthly average day and the average of that month's ranges.

### Validation against Fitbit (Sept 2026)

Fitbit's daily loads (read off its Week and Month charts) against openFit's for the same days, with temporary logging of formula variants (17 days; Sept 8 excluded — Health Connect only had 93 minutes of heart rate). Sum of squared errors / total bias:

| Variant | SSE | Bias |
| --- | --- | --- |
| neutral exponent, max HR 220 − age (old default) | 1295 | −121 |
| female k = 1.67 | 2483 | −167 |
| male k = 1.92 | 462 | −70 |
| male, no movement gate | 170 | −34 |
| male, 30–40 % HRR weighted 0.70 (best weight) | 182 | −13 |
| **male, max HR 208 − 0.7 × age (Tanaka)** | **117** | **−10** |

openFit now uses Tanaka's max HR for Cardio Load only (`cardioMaxHeartRate`); AZM zones keep 220 − age as Fitbit documents. The sex exponent matters a lot, so the profile's sex must be set (this user's data fits k = 1.92). Week of 20–26 Sept after the change: Fitbit 18/103/49/14/77/6/11 vs openFit 14/102/44/15/79/6/10. Day loads are cached under a formula version (`LOAD_FORMULA`), bumped whenever the formula changes.

## Active Zone Minutes targets (`Targets.kt`)

The user only picks a goal, **Maintain** or **Improve** (Profile). Everything else is derived.

- `completedWeeks`: the last 4 Sunday-start weeks. A week counts if it has ≥ 4 tracked days, and is scaled to 7 days.
- `habitualLoad` = max(4-week mean, EWMA with α = 0.4) (the white paper's eq. 3).
- Weekly AZM: guideline 150 (Maintain) or 300 (Improve). Below the guideline ramp up by min(1.1 · habitual + 10, guideline); at or above it Maintain keeps the habitual level and Improve uses 1.05 · habitual, capped at 600.
- Daily AZM (`dailyTarget`): remaining weekly ÷ days left, × min(1.15, 0.5 + readiness/100), capped at 2 × the even share; illness → ≤ 0.25 × even share; low readiness → ≤ even share.

## Hourly activity (`HourlyActivity.kt`)

- Window 09:00–17:00 (9 hours).
- An hour is met at ≥ 250 steps.
- Statuses: Met, Missed, Upcoming.

## Ideas not yet used

- pulse-mobile has a recovery and strain engine (`docs/engine-plan.md`) with HRV-CV and
  sleep-debt terms. These could refine readiness. Treat that repo as a spec only.
- An ML model isn't warranted yet. The rule-based targets need a few weeks of side-by-side data
  against Fitbit first. If they drift, fit the constants (readiness weights, progression rate) by
  regression on that data rather than training a model.
