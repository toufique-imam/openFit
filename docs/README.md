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
- **Cardio Load paper**: [arXiv 2508.11613](https://arxiv.org/html/2508.11613v2) (Google, 2025).

## Roadmap

Roughly in priority order. Details are in the linked files.

1. **Cardio Load target range.** Fitbit shows "Target 11–46" and draws the range as a band on
   the arc. openFit shows a single daily number. See algorithms §Targets and UI §Big ring.
2. **Missing Today tiles**:
   - Health Metrics ("3 of 3 · In personal range")
   - Stress and mindfulness (Mindful days, Stress Management Score)
   - Richer subtitles (Sleep Score, "9 kg left to lose", "2,000 cal below target")
3. **App chrome**:
   - Bottom navigation (Today / Coach / You)
   - The + FAB
   - Fitbit-style header (battery chip, messages, avatar)
   - Sticky compact date header while scrolling
4. **Icons.** Fitbit uses Material Symbols that the bundled icon set lacks: a heart-with-ECG for
   Cardio Load, a dumbbell, a sneaker for Steps, and a moon with a sparkle for Sleep. Add them as
   vector drawables; Material Symbols are Apache-2.0.
5. **Calibration against Fitbit.** Compare our Active Zone Minutes, Cardio Load and Readiness with
   the live app over a few weeks and tune the constants. Our AZM currently reads higher than
   Fitbit's.
6. **Google Health sign-in.** The code is in place, but the OAuth client still has to be created in
   Google Cloud (Android client, package + SHA-1, Health API scopes).
7. **Detail screens.** Tapping a tile opens a detail page in Fitbit; openFit has none yet.
