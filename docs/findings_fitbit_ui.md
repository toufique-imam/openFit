# Fitbit 4.67 UI spec

## How the numbers were measured

The values come from three sources, cross-checked against each other:

- The decompiled code (see [findings_jadx.md](findings_jadx.md)).
- Pixel measurements of screenshots from the live app on a 360dp-wide phone (480 dpi, 3 px/dp).
- The reference screenshots in `com.fitbit.FitbitMobile_4.67/ss/`.

Unless noted, the values are dp or sp in the dark theme. The openFit implementation is in
`ui/theme/Theme.kt`, `ui/today/Gauges.kt`, `TodayTiles.kt` and `TodayScreen.kt`.

## Tokens

### Colors

| Token | Value |
| --- | --- |
| background | `#101413` |
| card (surfaceContainerLow) | `#191C1B` |
| onSurface / onBackground | `#E0E3E1` |
| onSurfaceVariant | `#BFC9C4` |
| Fitness accent (mint) | `#78F8DD` (light theme `#008673`) |
| Wellness accent (lavender) | `#E5CCFF` (light theme `#856BAA`) |
| Heart / health (blue) | `#94D6FF` |
| Nutrition (pink) | `#FFD8E6` |
| Gauge track | accent at **16 % alpha** |

### Type

The bundled font is Google Sans Flex (OFL, `res/font/`), using the `ss02` stylistic set and
opsz = font size. Material 3 sizes:

| Style | Size / line height |
| --- | --- |
| titleSmall, labelLarge | 14/20, weight 500 |
| titleMedium | 16/24, weight 500 |
| headlineSmall | 24 |
| displaySmall | 36/44 |
| Section header | 18/24 |

## Today layout (top to bottom)

1. **Header.** Fitbit shows a battery chip, the "fitbit" wordmark, a messages icon and the account
   avatar. Not decoded; openFit shows its own simpler header.
2. **Date bar.**
   - Previous and next buttons are 40dp IconButtons with 24dp icons. The title is titleMedium,
     centered, and its slot width = total − 2·max(left slot, right slot).
   - **Today:** `<  Today  [pencil]`. The pencil customizes the dashboard; openFit doesn't have
     that yet.
   - **Other days:** `<  Fri, 25 Sept  [calendar] >`. The calendar icon **jumps back to today**;
     it is not a date picker. openFit also opens a date picker when you tap the title.
3. **Focus block.** Padding start 16, top 24, end 16, bottom 16.
4. **Sections:** Recovery, Activity, Health, Stress and mindfulness, Nutrition. Section headers are
   18sp. Tiles are cards with radius 16 and padding 16.

## Big dual ring (focus block)

- **Size.**
  - Canvas 240 wide; the layout counts only 230 (placed at −5).
  - Stroke 10, round caps. The arc centerline is inset 5, so it is Ø230 (outer Ø240).
  - Measured on device: the ring is 240 tall, i.e. square.
- **Top arc (Cardio Load, mint).** Starts at 187.5°, sweeps +165° clockwise over 12 o'clock.
- **Bottom arc (Readiness, lavender).** Starts at 172.5°, sweeps −165° over 6 o'clock.
- **Progress.** Both arcs fill starting from the 9 o'clock side. Angles use the Compose
  convention: 0° = 3 o'clock, clockwise positive.
- **Gap.** 7.5° each side of horizontal, so 15° between the arc ends at 3 and 9 o'clock.
- **Target band (not built yet).**
  - The track and progress are cut away 2dp around the target range.
  - The range itself is a hollow capsule outline (2dp) in the full accent color.
  - Fitbit uses this for "Target 11–46".
- **Text column inside the ring.** For each metric:
  - Label row: 16dp icon tinted with the accent, 2dp gap, label in labelLarge (14/20, w500) in
    onSurfaceVariant.
  - 8dp gap.
  - Value: 18sp/18 w500 in onSurface, centered.
  - 18dp gap between the two metric blocks. This spacing is measured, not decoded.
- **Position.** The ring's visual top is about 46dp below the date-title center.

## Mini gauge row (below the ring)

- **Spacing.** The 66dp gauge box starts 27dp below the ring's visual bottom.
- **Layout.** A row inside the 16dp page padding; each of the 3 tiles takes `weight(1f)`, so tile
  centers are at 70.7 / 180 / 289.3dp.
- **Gauge.**
  - 66dp box, 6dp stroke, round caps. The stroke **overflows** the box by 3dp: the centerline is
    on the box edge, so the visual size is 72.
  - Open at the bottom: start 126°, sweep 288°.
  - 24dp icon in the center.
- **Value.** 4dp below the gauge box. The large part is headlineSmall 24sp; units and "of N" are
  14sp. The value block is about 34dp tall.
  - Mini row: "5 of 5" shows "of 5" small.
  - Large tiles: "4 of 9" shows both numbers large.
- **"No data".** 14sp w500, baseline-aligned with where the digits would be.
- **Label.** titleSmall 14/20 in onSurfaceVariant, directly below.
- **Empty sleep.** Instead of a gauge, a filled 66dp circle in the track color with the icon.

## Gauge details (66dp, also used in tiles)

- **Zero progress:** a dot with the stroke's diameter at the start point.
- **Goal reached:** a 12dp check icon in the bottom opening, 2dp from the bottom. On the 154dp
  gauge it is 20dp, 4dp from the bottom.
- **Over 100 %:** Fitbit draws only the fractional lap. The track is clipped around the tip with a
  2dp gap. openFit doesn't do this yet.
- **Segmented ring** (Exercise days): 66 / 6, one segment per goal day.
  - Gap between segments is 0.06 × 360° = 21.6° along the centerline, which matches the screenshots.
  - The segment shape code didn't decompile.
- **154dp gauge** (when the primary metric isn't the dual ring): stroke 10, icon 64. Value in
  displayMedium 45 with headlineMedium 28 for units.

## Tiles (sections)

- Title in titleSmall 14/20 w500.
- Value in displaySmall 36/44, with the unit at 22sp (about 0.611 em).
- Subtitle in bodyMedium 14/20, onSurfaceVariant.
- Gaps: title→value 8, value→subtitle 4. Gauge 66dp on the right.
- Empty values read "No data" or "No score" at 22sp, with a filled circular badge instead of a ring.

## Known differences still open

- **Icons:** heart-with-ECG (Cardio Load), dumbbell (Exercise), sneaker (Steps), moon with sparkle
  (Sleep).
- **Missing tiles:** Health Metrics, Mindful days, Stress Management Score.
- **Missing chrome:** bottom navigation, the + FAB, the Fitbit header, a sticky date header.
- **Missing rendering:** target-range band and second-lap rendering.
