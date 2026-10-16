# Fitbit 4.67 teardown (jadx / apktool)

## What the teardown contains

- **Location:** `com.fitbit.FitbitMobile_4.67/`. **Gitignored. Read only.** Never commit it or copy
  code, strings or assets from it; use it to understand behaviour and measure the UI, then
  reimplement.
- **Folders:**
  - `base/`: apktool output (smali, decoded `res/`).
  - `jadx/sources/`: decompiled Java. Most of the app is in the obfuscated `defpackage/`.
  - `jadx/resources/res/`: resources (`values/strings.xml`, drawables, fonts).
  - `ss/`: screenshots of the old app, grouped by screen. `ss/Today/` has a full-length Today
    screenshot.

## How to navigate

- The code is ProGuard-obfuscated, but string literals, `R.string` names, enum names and some
  package names survive. Useful packages:
  - `com/google/android/apps/fitbit/app/today/`
  - `.../heartratezones`
  - `com/fitbit/azm`
- Start from a visible string. Find its `R.string` id in `strings.xml`, grep for the id or the
  name, then follow the composables.
- Compose code decompiles to static methods with `(..., gwh composer, int changed, int default)`
  signatures:
  - `gaj.A(n)` is n sp.
  - `X` is the Typography object and `U` the ColorScheme. Their field letters map to M3 slots
    (`U.o` = onBackground, `U.q` = onSurface, `U.s` = onSurfaceVariant).
- Some large composables failed to decompile (jadx emits a "Method dump skipped" comment). Read
  the smali in `base/` for those, or measure them on device.

## Server-side scores

The APK has no formulas for Active Zone Minutes limits, Sleep Score, Daily Readiness or the Cardio
Load target. It fetches them from Fitbit's backend. openFit's versions are its own; see
[findings_algorithms.md](findings_algorithms.md).

## Class map (Today screen)

Paths are relative to `jadx/sources/defpackage/`. Names are obfuscated and will differ in other
versions.

| Class / method | Role |
| --- | --- |
| `aipz` | Today tile catalog enum (includes `READINESS_AND_CARDIO_LOAD`) |
| `azps` | Tile size enum: `a` PRIMARY_FOCUS_METRIC, `b` SECONDARY_FOCUS_METRIC, `c` PILLAR_METRIC, `d` PILLAR_GROUP |
| `azoz(aipz, azps)` | DI key; `mvn.dp()` (around lines 4524–4626) maps keys to providers built in `mvm` |
| `azkm` (74, 87) | Picks the primary or secondary renderer for a metric |
| `bmum.bG` (~2142) | Whole focus block. `bH` is the phone column variant, `bI` the wide-screen row variant |
| `bmum.bJ` / `bK` (~2533 / ~3116) | Secondary tile column: gauge, value, label |
| `xos` (built at `mvm` ~2975) | Cardio Load + Readiness dual-ring tile |
| `wxw` (222–283) | Builds the two arc data objects (`bhgu`) for the dual ring |
| `bhhd` | Dual ring: `i` resolves colors, `b` draws the ring, `c` label row, `e` value, `f` one arc with optional target band |
| `bozi` | Arc helpers: `cP` open-ring angles, `cQ` explicit angles, `cU/cV/cX/cZ` round-cap stroke arcs, `cS/cT/cY` target band |
| `bnoi.bq` / `bt` / `br` | 66dp gauge, 154dp gauge, check-mark overlay (`bs`, the shared body, didn't decompile) |
| `bhkm.c` | Animated gauge variant whose parameters are readable (progress stroke, track stroke, overflow outline) |
| `bhki`, `bhia`, `bhih` | Arc drawing, second-lap clipping, check sizes |
| `bhlv.A` → `bhlv.B` | Segmented ring for Exercise days (`B` didn't decompile; segment gap argument `0.06f`) |
| `angc` | Exercise days tile (`exercise_focus_metric_title`, "^1 of ^2") |
| `xxp` / `xto` | Steps tile / value formatting |
| `bhlg`, `bhla` | Ring with a target range (sleep) |
| `bhpn.b`, `bedc` (104–123), `bhng` (57–107) | Text builder. `<annotation key="style">` spans map to SMALL, MEDIUM, MEDIUM_LARGE or LARGE text styles |
| `bhtz`, `bhtx`, `bhsb` (53) | Typography constants and assembly |
| `bhtc.b` (~249), `bhrj`, `bhra`, `bhrt` | Theme colors; track alpha 0.16 is in `bhra.j` |
| `bhnq.u` → `w` / `x` | Date bar, default and "expressive" variants; layout in `bkfy` (57–82), slots in `ayov` (460–590) |
| `bhmp` / `bhmo` / `bhmq` | Previous, next and action buttons in the date bar |

Two theme paths exist. The default is used when `bhsc.a == false`; the alternate "expressive"
variant is gated by a flag in `adlk` (~72). openFit follows the default path.

## Not decoded yet

- The Today header row (battery chip, wordmark, messages, avatar).
- The text-column layout inside the dual ring (`czo.q`). Spacing was measured on device instead.
- The segmented-ring segment geometry (`bhlv.B`). The gap was measured on device.
- Detail screens behind each tile.
- The Health Metrics, Stress and Mindfulness tiles.

## Cardio Load detail screen (decompiled)

| Class | Role |
| --- | --- |
| `tln.n` / `tln.o` | Screen shell (Scaffold, top bar, tab row) |
| `tln.m` | Overflow menu |
| `tln.s`, `r`, `q`, `p` | Week/Month/Year layout, header, chart, history list |
| `xlw.b` | Day tab section order |
| `xlw.h` | Day header |
| `xlw.g` | Day chart (212dp) |
| `xlw.i` | Workouts list |
| `xlw.a` | Breakdown |
| `xlw.e` | Seven-day training |
| `xlw.c` | Fitness target |
| `xmu` | Day state: status text, description choice, breakdown percentages |
| `xns` | Week/Month/Year state |
| `xnu` | History list |
| `xkh` | Helpers: bucket top, progress, axis max, status text |
| `xke` | Acute:chronic bands |
| `xkd` and subclasses | The six bar segments |
| `nfe` | Y-axis label rule |
| `bgld` | X labels |
| `bfjw.gp` | Curve smoothing |
| `bfon` case 12 | Legend swatch |
| `bhlg` / `bhla` | 66dp gauge with target band |
| `aapd` | Network client |

**Server-side.** The client computes none of the target range, the ACWR or the scale. It fetches them:

- **Daily target:** a goal `(lowerValue, upperValue)` from the goals store.
- **Ratio:** `cardio-acute-chronic-workload-ratio`, one double per day.
- **Scale:** `cardio-load-observed-interval` buckets, from very low to very high. The ring and day-chart scale is the "very high" upper bound.
- **Calibration:** `calibrations/cardio-load`, giving a status and the remaining days.
- **Loads:** rollups of `CardioLoad` and `CardioLoadPerMinute`, each with `combined`, `active` and `passive`.

openFit's models for these are in [findings_algorithms.md](findings_algorithms.md).

**Client-side rules:**

- **Status text:** "Calibrating", "No target", "Target lo-hi" (today) or "Below target" (past), "In target" (inclusive), "Above target".
- **Breakdown:** the logged share is min(non-manual workout load / combined, 1).
- **Training status:** the ratio is clamped at 1.89 and truncated to 2 decimals, then banded: < 0.4 undertraining, < 0.8 at risk, < 1.1 maintaining, < 1.3 improving, < 1.5 at risk, else overtraining.
- **Month headline:** the average daily ratio (needs ≥ 7 days), banded 0.8 / 1.1 / 1.3.
- **Values:** loads are int-cast (truncated). Averages skip zero days.

## Goals screen (decompiled)

`anye.g` renders one category of the Goals list (`goals_l2_screen_app_bar_title`): rows 4dp apart, 16dp side margins.

**Categories:** Activity, Health, Mindfulness, Nutrition, Sleep (`goal_list_category_title_*`). The You tab card shows "%1$d active" (`goals_category_card_description`).

**Goal ids** (`anye.i`, "GoalsRenderingId"):

| Category | Ids |
| --- | --- |
| Sleep | BEDTIME, WAKE_UP_TIME, SLEEP_SCHEDULE, DAILY_SLEEP_DURATION |
| Activity | DAILY_STEPS, DAILY_DISTANCE, DAILY_ENERGY_BURNED, DAILY_FLOORS, DAILY_ACTIVE_MINUTES, DAILY_ACTIVE_ZONE_MINUTES, EXERCISE_SESSIONS, HOURLY_ACTIVITY, CARDIO_LOAD, RUNNING_MILEAGE |
| Nutrition | DAILY_ENERGY_CONSUMED, DAILY_WATER_CONSUMED |
| Health | WEIGHT, BODY_FAT_PERCENTAGE, BLOOD_GLUCOSE |
| Mindfulness | MINDFULNESS_SESSIONS |

**Row rules** (`anye.h`):
- A goal whose state is DISABLED or UNSPECIFIED shows a "Set goal" button (`goal_list_item_set_goal`).
- A set goal shows its value with a meta line: "Daily" (`goal_list_item_meta_daily`), "Weekly", "Target value" or "Target range" (blood glucose).
- "Reset goal" asks "Reset %1$s?" and leaves the goal blank until a new one is set (`reset_goal_dialog_message`).

**You tab cards** (`GoalsYouSectionComposerViewModel`, `anwc` case 9):
- The user's goals are grouped by category; a card shows "N goals" (`goals_you_section_goals_num`), so a category with no goals has no card.
- Order: sleep 0, fitness ("Activity") 1, body_measurement ("Health") 2, mindfulness 3, nutrition 4. The Goals list (`aoqo`) uses the same order.
- Icons: `gs_sleep`, `gs_steps` (a shoe), `gs_bar_chart_4_bars`, `gs_mindfulness`, `gs_nutrition` (an apple).

**Row titles by goal:** Bedtime, Wake time, Sleep duration (all "Daily"); Steps, Distance, Energy burned, Floors, Active Zone Minutes, Hourly activity ("Daily"); Exercise days, Running distance, Mindful days ("Weekly"); Weight, Body fat percentage ("Target value"); Food, Water ("Daily"). CARDIO_LOAD shows as "Heart" with "Daily".

The live Goals screens no longer load (the app is discontinued and needs Fitbit's servers), so this section is the reference for openFit's Goals screen.
