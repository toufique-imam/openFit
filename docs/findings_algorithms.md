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

Fitbit's own description: the score "compares your sleep, resting heart rate, and heart rate variability (HRV) against your personal baseline", needs 7 nights to be reliable, takes HRV from the longest sleep (≥ 3 h) of the past 24 h against "your HRV over the past month", and bands Low 1–29 / Moderate 30–64 / High 65–100. The formula itself is server-side; openFit's is a z-score blend against a 30-day baseline:

| Component | Weight | Signal |
| --- | --- | --- |
| HRV | 0.7 | ln(HRV); half today's z, half the 7-day mean's z |
| Resting HR | 0.2 | negative z (lower is better) |
| Sleep | 0.1 | Weighted last 4 nights (0.4/0.3/0.2/0.1) vs baseline; −1 z penalty under 6 h |

- Each z is clamped to ±3; minimum SDs: ln-HRV 0.05, resting HR 2 bpm, sleep 0.5 h. Confidence ramps 0 → 1 as the baseline grows from 4 to 14 nights.
- **Score = 60 + 24 · Σ(w·conf·z) / Σw**, clamped to 1–100.
- **Calibration (Oct 2026):** Fitbit's daily scores for 13–30 Sept were read off its month chart (bars match its labelled week to ±0.5). With openFit's own z-scores the old weights (0.4/0.3/0.3, 50 + 20R) were off by 14 points RMS and compressed toward 50; a grid search gave center 60, gain ≈ 24 and HRV-dominant weights (RMS 8.4–8.8). Only 17 days and baselines of 5–22 nights were available (Health Connect data starts 8 Sept), so revisit once a full month of baseline exists. Fitbit's 23 Sept score (88) is not explained by HRV, RHR or sleep, and Fitbit's readiness strings mention activity, so recent load may be an extra input.
- **No score** unless last night's sleep exists and at least two components are available.
- **Illness flag** (any of): resting HR ≥ baseline + 7 bpm; ≥ baseline + 4 bpm for 3 days; resting z ≤ −2.5 with HRV z ≤ −2.0.
- **Breakdown** ("What impacted your readiness"): HRV level from last night's ln-HRV z (±1 usual, ±2 much); resting HR as the bpm difference from the baseline mean; past week of sleep Good (z ≥ −0.5) / Fair (≥ −1.5) / Poor.
- **Period takeaway:** "Mostly high/moderate/low" when one level covers more than half the scored days, otherwise "Mixed" (Fitbit called 57/79/57/88/65/48/26 "Mixed").

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

## Hourly activity glance (known gap)

Fitbit's "Your day at a glance" splits time into active and stationary minutes using the watch's own motion classification (`breakdown_section_stationary`). Health Connect only exposes step records, which Fitbit writes in coarse intervals, so a minute-level "no steps" proxy overstates inactivity badly.

For Thu 15 Oct:

| | Fitbit | openFit |
| --- | --- | --- |
| Active | 2 h 34 m | 55 min |
| Inactive | 1 h 12 m | 7 h 38 m |
| Longest inactive period | 23 min | 3 h 34 m |

The hours-with-250-steps counts match Fitbit exactly (2 of 9 that day; 15 hours that week). A better proxy needs a motion signal: heart-rate variability while awake, or exercise and step density over 5-minute windows. Until then the glance is approximate.

## Sleep Score (`Sleep.kt`)

Fitbit's Sleep Score is computed on its servers. openFit keeps Fitbit's published shape and fits the constants to Fitbit's own scores:

- **Duration (up to 50):** time asleep ÷ 7 h 45 m, capped at 1.
- **Quality (up to 25):** deep + REM minutes ÷ 130, capped at 1. Nights without stage data get half.
- **Restoration (up to 25):** 18 − 0.6 × the night's awake percentage.
- **Ratings:** 90+ excellent, 80–89 good, 60–79 fair, below 60 poor.
- **No score:** a main sleep under 3 hours.

Fitted to the five nights with stages that Fitbit scored:

| Night | Asleep | Deep + REM | Awake | Fitbit | openFit |
| --- | --- | --- | --- | --- | --- |
| 24 Sept | 298 m | 122 m | 24 % | 58 | 59 |
| 12 Oct | 381 m | 171 m | 10 % | 78 | 78 |
| 13 Oct | 308 m | 131 m | 22 % | 65 | 63 |
| 14 Oct | 316 m | 140 m | 16 % | 68 | 67 |
| 15 Oct | 472 m | 196 m | 11 % | 85 | 87 |

Every night is within 2 points. Several parameter sets fit about as well, so treat the split between the three parts as unconfirmed.

Health Connect's stage minutes agree with Fitbit's timeline:
- 24 Sept: REM 53 vs 54, Deep 69 vs 70, with the same 2:21–9:09 am window.
- Total sleep reads 1–2 % lower than Fitbit's.

**"Current sleep schedule":** in Fitbit this is the user's own bedtime target. openFit shows the 30-night median instead, until a schedule setting exists.

## Heart-rate zones (`ZoneMinutes.kt`)

Thresholds sit at 40 %, 60 % and 85 % of heart-rate reserve, with max HR 220 − age. Fitbit rounds each threshold **up**: rest 61, max 190 gives 113 / 139 / 171, matching its Heart chart.

Fitbit's "N zones" on a day counts only moderate, vigorous and peak. For Thu 15 Oct openFit's minutes in zones were:

| Zone | Fitbit | openFit |
| --- | --- | --- |
| Moderate | 39 | 40 |
| Vigorous | 7 | 8 |
| Peak | 6 | 6 |

