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

## Targets (`Targets.kt`)

The user only picks a goal, **Maintain** or **Improve** (Profile). Everything else is derived.

### Weekly targets

- `completedWeeks`: the last 4 Sunday-start weeks. A week counts if it has ≥ 4 tracked days, and is
  scaled to 7 days.
- `habitualLoad` = max(4-week mean, EWMA with α = 0.4).
- **Cardio Load weekly target**
  - Maintain: max(floor, habitual).
  - Improve: max(floor, min(1.10 · habitual, 1.3 · 4-week mean)). The cap keeps the
    acute:chronic workload ratio ≤ 1.3.
  - Floor: median weekly CL/AZM ratio × 150, so that "150 guideline minutes" is expressed in load
    units. It defaults to 130 when there's no history.
- **AZM weekly target**
  - Guideline: 150 (Maintain) or 300 (Improve).
  - Below the guideline: ramp up by min(1.1 · habitual + 10, guideline).
  - At or above it: Maintain keeps the habitual level; Improve uses 1.05 · habitual, capped at 600.

### Daily target (`dailyTarget`)

1. Spread the remaining weekly target evenly: remaining ÷ days left in the week.
2. Scale by readiness: × min(1.15, 0.5 + score/100). No readiness means × 1.
3. Apply caps:
   - ≤ 2 × the even daily share, so a lazy start can't push catch-up too far.
   - Acute:chronic: today ≤ 1.3 · chronic weekly − the previous 6 days. Only applies once chronic
     ≥ 70.
   - Illness flag → ≤ 0.25 × even share.
   - Low readiness → ≤ even share (no catch-up).
4. "Week done" shows once the weekly target is met.

### Future: target range

Fitbit shows the Cardio Load target as a range ("6 · Target 11–46") and draws it as a band on the
arc. Two candidate approaches:

- Derive the band from the same daily number (e.g. 0.5×–1.5×).
- Make the lower bound "maintain" and the upper bound "improve".

The arc already supports this geometrically; see the UI findings.

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
