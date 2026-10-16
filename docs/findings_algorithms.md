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
- **No score** unless last night's sleep and HRV exist and at least two components are available. Fitbit showed "No score" on 6 Oct 2026, the one night Health Connect had no HRV.
- **Recheck (16 Oct 2026, 26 days with a 14+ night baseline):** openFit is 9.4 points RMS from Fitbit. Refitting the weights by least squares (with each day held out in turn) gave 9.5 at best, so the model stands. The misses are days Fitbit scores far from the signals: 23 Sept (Fitbit 88, openFit 65), 7 Oct (15 vs 37), 5 Oct (32 vs 45).
- **Illness flag** (any of): resting HR ≥ baseline + 7 bpm; ≥ baseline + 4 bpm for 3 days; resting z ≤ −2.5 with HRV z ≤ −2.0.
- **Breakdown** ("What impacted your readiness"): HRV level from last night's ln-HRV z (±1 usual, ±2 much); resting HR as the bpm difference from the baseline mean; past week of sleep Good (z ≥ −0.5) / Fair (≥ −1.5) / Poor.
- **Period takeaway:** "Mostly high/moderate/low" when one level covers more than half the scored days, otherwise "Mixed" (Fitbit called 57/79/57/88/65/48/26 "Mixed").

## Cardio Load target range (`CardioTargets.kt`)

Fitbit 4.67 gives a **daily target range** ("Target 11-46"). Its strings say the range is "personalized based on your recent cardio load and your readiness", and that the fitness target (maintaining / improving) sets "how challenging your daily target load will be". The 2025 white paper (`docs/2508.11613v2.pdf`) describes the newer weekly target, not this one, so the daily model was reverse-engineered.

**Data (16 Oct 2026):** Fitbit's own daily loads from 9 Aug (its Week lists) and its daily ranges for 7-25 Sept (its Day view). From 26 Sept on, Fitbit shows "No target" every day, including today: its servers stopped sending targets, so those days say nothing about the algorithm.

The bounds move day to day by exactly the load that enters or leaves a rolling 7-day window, so the range is "today's load that brings the last 7 days to a window around the chronic weekly load":

- **chronic** = 28-day rolling mean of weekly load before today (four rolling 7-day blocks, each needing 4 or more tracked days).
- **range** = [low, high] x chronic - (load of the previous 6 days), with:
  - Maintain: 1.039-1.144, fitted. Improve: 1.139-1.244 (assumed +0.10; no Fitbit data).
  - Fitbit's **minimum target 4-24**: one value for all users (white paper 3.3). It applied on 15, 18 and 23 Sept.
  - Readiness 43-88 left the window unchanged, so only low readiness (29 or below) moves it (-0.10, assumed and untested). 18 Sept had no readiness score but still a target, so readiness is not required.
  - Illness flag: 0 to 0.05 x chronic (assumed).
- **No target** only without a week of history.
- **Ring and Day-chart scale** = 0.45 x chronic (Fitbit's day-chart axis of 139 = 0.45 x 310).
- **Fit:** 17 of 19 days within 2 of both of Fitbit's bounds (sum of squared errors 59, down from 89 before the refit). 11 Sept is off by 6 at the top (Fitbit 6-35, model 4-29).

### Training status ("Your past seven days of training")

Fitbit: "Every day, Fitbit looks at how much cardio load you've earned the past 7 days and compares it to the past 28 days." openFit: acute = last 7 days (≥ 4 tracked, scaled), chronic = mean of the 28-day blocks (needs ≥ 2). Thresholds (assumed, standard ACWR bands): < 0.6 undertraining, < 0.8 at risk of undertraining, < 1.1 maintaining, < 1.3 improving, < 1.5 at risk of overtraining, else overtraining. Fitbit's bar has six equal segments (yellow, mint, mint, teal, teal, yellow); the marker is placed linearly within its segment.

### Week / Month / Year views (`CardioPeriods.kt`)

- Points sit at x = 24 + i × 288 / n dp; each has its own range box (one slot wide).
- Axis top = 1.2 × the highest value or band; middle label = top / 2 rounded **up** to a multiple of 5 (Fitbit: 123/65, 106/55, 139/70).
- Month list = the Sunday weeks touching the month, newest first, as daily averages ("27 Sept – 3 Oct").
- Month headline = the month's average day vs the 28 days before it: < 0.8 undertrained, < 1.15 maintained, < 1.3 improved, else overtrained. Fitbit called September (44/day after August's 40, ratio 1.10) "Maintained fitness", so the improve threshold is above 1.10.
- Year points = monthly average day and the average of that month's ranges.

### Validation against Fitbit (Sept 2026; rechecked Oct 2026)

**Recheck (16 Oct 2026):** across 37 days, 9 Sept - 15 Oct, openFit's loads against Fitbit's sum to 456 squared error with +12 bias. Most days are within 5; the largest misses are 30 Sept (+9), 5 Oct (+10) and 12 Oct (+7), all evening workouts. A grid over the threshold, weight, exponent, movement gate and gap filling found nothing clearly better, so the formula stands.

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

- The window is the user's hourly goal (start and end hour). Fitbit's usual 9am–6pm gives 9 hours, 09:00–17:00.
- Unset, the tile reads "No goal set" and the screen offers to set it.
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

Fitbit's Sleep Score is computed on its servers. Its published shape is duration, quality (deep + REM) and restoration (time awake); openFit fits a linear version to Fitbit's own scores.

**Fitbit scores the main sleep only.** On 5 Oct 2026 the day had 9 h 05 m asleep, but the main sleep was 5 h 39 m with a quarter of it awake, and Fitbit scored 54. The old formula used the day's total and gave 72.

**Score** = 41.3 + 4.37 x hours asleep (capped at 7 h 45 m) + 0.42 x deep-and-REM % of the time asleep - 0.86 x awake % of the night. Nights without stages count a typical 38% deep and REM.

- **No score** under 3 hours asleep (18 Sept: 1 h 21 m; 6 Oct: 3 h 48 m in bed).
- **Ratings:** 90+ excellent, 80-89 good, 60-79 fair, below 60 poor.
- **Fit (31 nights, 9 Sept - 16 Oct):** 3.2 points RMS, 27 nights within 3, against 6.8 for the old formula. Leaving each night out in turn gives 3.7. The worst miss is 28 Sept (Fitbit 70, openFit 60).

Health Connect's stage minutes agree with Fitbit's timeline:
- 24 Sept: REM 53 vs 54, Deep 69 vs 70, with the same 2:21–9:09 am window.
- Total sleep reads 1–2 % lower than Fitbit's.

**"Current sleep schedule":** the user's own bedtime and wake-time goal, as in Fitbit. Until it is set, the Sleep screen offers "Set a sleep schedule", and "days on schedule" shows a dash. A night is on schedule when both ends are within 30 minutes of the goal.

## Heart-rate zones (`ZoneMinutes.kt`)

Thresholds sit at 40 %, 60 % and 85 % of heart-rate reserve, with max HR 220 − age. Fitbit rounds each threshold **up**: rest 61, max 190 gives 113 / 139 / 171, matching its Heart chart.

Fitbit's "N zones" on a day counts only moderate, vigorous and peak. For Thu 15 Oct openFit's minutes in zones were:

| Zone | Fitbit | openFit |
| --- | --- | --- |
| Moderate | 39 | 40 |
| Vigorous | 7 | 8 |
| Peak | 6 | 6 |

## Heart range (`HealthConnectSource.load`)

Fitbit's day range ("49-188 BPM") is the lowest and highest one-minute average, not the raw samples: on 15 Oct 2026 the minute averages gave 49-188 exactly, while the raw samples gave 46-190.
