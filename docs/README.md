# openFit docs

openFit recreates the Today dashboard of the pre-"Google Health" Fitbit app (v4.67) on top of
Health Connect and the Google Health API. These notes record what has been learned so far and what
is left, so that work can resume without re-deriving it.

| File | What it covers |
| --- | --- |
| [findings_algorithms.md](findings_algorithms.md) | Cardio Load, Daily Readiness, automatic weekly/daily targets, Active Zone Minutes, hourly activity |
| [findings_data_sources.md](findings_data_sources.md) | Health Connect and Google Health API behaviour, merging, caching, rate limits |
| [findings_fitbit_ui.md](findings_fitbit_ui.md) | Measured Fitbit UI spec (tokens, type, ring and gauge geometry) and how openFit matches it |
| [findings_jadx.md](findings_jadx.md) | How to navigate the decompiled 4.67 APK and a map of the classes found so far |

## Reference material

- **Fitbit 4.67 teardown**: `com.fitbit.FitbitMobile_4.67/` (gitignored). It holds apktool output in `base/`,
  jadx sources in `jadx/`, and screenshots of the old app in `ss/`. It is for reading only.
  Never commit it or copy its code, strings or assets; reimplement the behaviour.
- **Live reference**: a test phone with Fitbit 4.67 installed. Google Play keeps offering the
  Google Health update. Always dismiss it with the X and never update.
- **pulse-mobile** (`D:\AndroidStudioProjects\pulse-mobile`): the owner's React Native health app. It is a
  knowledge base for ideas and cited formulas (`docs/engine-plan.md`,
  `src/screens/more/howItWorksContent.ts`). It is PolyForm Noncommercial licensed and partly ported
  from another app, so treat it as a spec only and don't copy code.
- **Cardio Load paper**: [arXiv 2508.11613](https://arxiv.org/html/2508.11613v2) (Google, 2025). A local copy
  may sit at `docs/2508.11613v2.pdf`; it is gitignored (all rights reserved).

## Status

**Built** (Fitbit 4.67 layout, with real data from Health Connect):
- Today: focus ring, mini gauges and tiles.
- Bottom tabs: Today, Coach, You.
- Detail screens:
  - Cardio Load
  - Daily Readiness
  - Steps, Distance, Floors and Energy burned
  - Active Zone Minutes
  - Heart (with 3 months)
  - Exercise
  - Hourly activity
  - Sleep
  - Health Metrics
  - Food, Water and Weight
- Goals (from the You tab): Sleep, Activity, Nutrition and Health, as in Fitbit's Goals list.
- Journal tab (in place of Fitbit's online Coach): daily behaviour check-ins and next-day insights. See [findings_journal.md](findings_journal.md).
- From pulse-mobile: Energy Bank (Recovery section) and Pulse Age (Health section). See [findings_pulse_features.md](findings_pulse_features.md).

**Goals are user input only.** No goal has a built-in value:
- An unset goal shows "Set goal" on the Goals list and on its screen, and tiles show the icon without a progress ring.
- Each screen's menu has "Set goal" or "Adjust goal". "Reset goal" clears one.
- The fitness goal (Maintain or Improve) always has a value, because the Cardio Load target and the weekly Active Zone Minutes goal follow from it.

**Calibrated against Fitbit's own numbers:**

| Measure | How close |
| --- | --- |
| Cardio Load | within a few points (needs the profile's sex) |
| Readiness | about 9 points RMS |
| Sleep Score | about 2 points |
| Zone thresholds | exact |
| Exercise and hourly counts | exact |

## Roadmap

1. **Stress and mindfulness:** mindful days from Health Connect, and a mindfulness goal. Fitbit's Stress Management Score needs EDA data Health Connect doesn't carry.
2. **Hourly activity glance:** Fitbit's stationary-time definition (see algorithms).
3. **Today chrome:** the header's battery chip and messages, the + FAB, and a sticky date header.
4. **Calibration:** more Readiness and Sleep Score data once a full month of baseline exists. The Cardio Load Improve window and readiness shifts are still assumed.
5. **Goals still missing:** body fat, running distance and mindfulness (Fitbit has them; openFit has no screens for them yet).
