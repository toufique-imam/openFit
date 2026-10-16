# Energy Bank and Pulse Age (from pulse-mobile)

Both are modelled on pulse-mobile's published explainer text (`src/screens/more/howItWorksContent.ts`) and the studies its
Pulse Age notes cite. openFit's code is its own: pulse-mobile is PolyForm Noncommercial, and its `src/core/engine`
re-implements another company's decompiled app, so it is not used even as a reference.

Pulse itself is installed on the test phone (`in.portlabs.pulse`). Its Settings show "Past data: Not allowed", so it only
sees data since it was installed.

## Energy Bank (`EnergyBank.kt`, `HealthConnectSlots.kt`, `EnergySource.kt`)

- **Slots:** 240 six-minute slots a day on the local-midnight grid, each with:
  - mean heart rate
  - asleep: the slot's midpoint is in a non-awake sleep stage of any session
  - workout: the slot's midpoint is in an exercise session
- **Stress (0–100):** where the slot's heart rate ranks among the awake, non-workout slots of the 30 days before.
- **Fit to Pulse (16 Oct 2026):** the rates in Pulse's explainer ("a full 7.5-hour night charges about 80") put openFit at 90-100% every morning, while Pulse's own app woke at 37-66% and ended days at 16-31%. Fitting to Pulse's daily wake level, end level, charged and drained for 1-14 Oct (12 days) gives:
  - asleep +0.55 per slot (about 41 a night);
  - calm +0.05 per slot;
  - drain 0.33 x stress / 40;
  - calm below stress 20;
  - edges of 40 points.

  That is 7.6 points RMS, against 34 before. On 16 Oct both read 27% at 16:18; openFit woke at 52%, Pulse at 39%.
- **Each slot moves the bank (rates before the fit):**
  - Asleep: +80/75 per slot (a 7.5-hour night charges about 80).
  - Awake with stress < 20: +15/165 per slot.
  - Awake with stress ≥ 20: −(60/165) × stress/50 per slot. Workouts drain the same way.
  - Not worn: no change.
- **Edges:** charging slows over the last 20 points below 100, and draining over the last 20 above 1. The level stays
  within 1–100.
- **Continuity:** the bank starts at 50, carries across midnight, and runs a 7-day warm-up before it is shown. Until the
  first week of data it reads "Calibrating".
- **Biggest drains:** each workout's total drain (named from the day's exercise sessions) and each hour of
  non-workout stress; the top three are shown.
- **Cache:** `SlotCache` (prefs `day_slots`, key `v1:date`) stores days from two days ago and older. The first run reads
  about 37 days of heart rate (about 20 s on the S20 FE); after that it is quick.
- **Screens:** a Today tile in Recovery, and a screen with Day (the level over 24 h, charged and drained, biggest
  drains) and Week (the daily peak).

Known gaps:
- There is no HRV term during sleep (Pulse's stress uses overnight HRV for 40%).
- A mid-morning level near 100 is normal after a full night: a 7.5-hour night charges more than a typical day drains.

## Pulse Age (`PulseAge.kt`, `PulseAgeSource.kt`)

Pulse Age = age + 5 × Σ ln HR. The present inputs are scaled up to stand for all nine, at least 5 are needed, and the
result is clamped to ±15 years.

| Factor | Curve (published) | Reference |
| --- | --- | --- |
| VO2 max | Kodama 2009: RR 0.87 per MET | WHOOP's recommended VO2 max by age and sex |
| Resting HR | Zhang 2016: RR 1.09 per 10 bpm | 60 bpm (men), 64 (women) |
| Steps a day | Paluch 2022 quartiles, under 60 and from 60 | 8,000 (under 60), 5,600 |
| Sleep hours | Saint-Maurice 2024: HR 1.29 at 5 h vs 7 h | 7 h |
| Sleep consistency (SRI) | Windred 2024 quintiles | 75.6 |
| Moderate min/week | Arem 2015 | 100 at 30, easing to 70 at 70 |
| Vigorous min/week | Ahmadi 2022 | 10 at 30, easing to 7 at 70 |
| Strength min/week | Momma 2022: RR 0.83 at 40 | 40 |
| Body fat | Jayedi 2022: HR 1.11 per 10 points; credit stops 5 under; none from 60 | 20% (men), 33% (women) |

**Why 5 years per unit:** mortality rises about 10% a year of age, so one unit of ln HR is about 10 years. Summing
separate studies overstates the joint effect (Li 2018), so openFit halves it; Pulse fits 4.77.

**Inputs** (the 28 days before the date):
- Sleep hours: mean of each night's total asleep.
- SRI: from every session's non-awake stages, minute by minute, comparing consecutive days.
- Steps: mean of days with steps.
- Resting HR: daily resting heart rate.
- Moderate minutes: fat burn + cardio zones; vigorous: the peak zone (openFit's 40/60/85% of heart-rate reserve). Both
  are the daily mean × 7.
- Strength: strength, weightlifting, calisthenics, HIIT, Pilates and yoga sessions, per week.
- VO2 max and body fat: the window's mean, or the latest within 90 days.

**Needs age and sex** from the profile; without them the tile reads "Add age and sex".

**Check on 16 Oct 2026** (with sex set only for the test, then restored):
- openFit 33.0 (+3.0 on age 30) against Pulse 31.1 (+3.1 on age 27.9).
- Both have no VO2 max data.
- Pulse averages 6 months; openFit 28 days.

Ideas not done:
- Pace of Aging (Pulse: the 30-day age held against 6 months).
- A history chart.
- Body fat estimated from BMI when only weight is logged (needs height in the profile).

## Stress (`EnergyBank.kt` stressDays, `ui/stress`)

- Built on the same six-minute slots as Energy Bank. Each worn slot (asleep too) ranks its heart rate among the awake, non-workout slots of the 30 days before. Workouts show no stress.
- **Shown stress = 0.75 x that rank.** It was fitted to Pulse's daily average and its time in each band for 30 Sep - 14 Oct 2026: 3.7 points from Pulse's average, low-stress time within 5% and high within 7%. A plain rank put 22% of the day in high stress against Pulse's 6%.
- Bands are Pulse's: 0-29 low, 30-59 medium, 60-100 high. Today shows the latest reading, past days the day's average. It needs 5 days of data.
- **Screens:** a Today tile in Stress and mindfulness, where Fitbit's server-only Stress Management Score was. The page has a Day view (bars by band over 24 h with sleep shaded; low, medium and high time) plus Week and Month.
- **On 16 Oct:** openFit gave 9 h 6 m low, 6 h medium and 1 h high; Pulse gave 10:00, 6:06 and 0:12.
- **Not done:** Pulse's HRV term during sleep, its "typical Friday" comparison, and Breathe.

## Cardio fitness / VO2 max (`Fitness.kt`)

- **VO2 max:** Health Connect's value when there is one. Otherwise Uth et al. 2004: 15.3 x (220 - age) / the 30-day average resting heart rate. Pulse's figure for this user, 47.9 at 190 / 60.7, fits it exactly.
- **Fitness level:** the percentile in the FRIEND treadmill table (Kaminsky et al. 2015) for the user's sex and age decade, read linearly between columns and kept within the 5th-95th. Bands: Superior 80+, Excellent 60-79, Good 40-59, Fair 20-39, Poor below.
  - Pulse's two worked examples reproduce exactly: a man of 35 at 45.0 sits at 59.6, and a woman of 52 at 30 sits at 83.2.
  - The table values themselves were not checked against the paper beyond those anchors.
- **On 16 Oct:** openFit gave 48.4, 72nd percentile (age 30, 30-39 row). Pulse gave 47.9, 50th, because it has the user's age as 27.9 and so uses the 20-29 row.
- **Today:** a "Cardio fitness" tile in Health ("Excellent · 72nd percentile · Estimated") opens Pulse Age.
- **Pulse Age:** an estimated VO2 max counts at half weight, as in Pulse. Pulse Age then read 32.1, against 33.0 without VO2 max.

