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
