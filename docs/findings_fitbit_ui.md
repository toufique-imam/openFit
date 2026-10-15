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

## Metric detail screens (Cardio Load, Readiness, Steps)

Shared frame (`ui/detail/DetailScaffold.kt`); values from the decompiled Cardio Load screen, cross-checked on screenshots.

- **Top bar**: back arrow, title in titleLarge 22/28, overflow menu. Cardio Load's menu: "Activity settings", "Manage data".
- **Tabs**: Day / Week / Month / Year, four 90dp tabs, 16sp Medium. Selected uses the metric accent, unselected #BEC9C3. Indicator is 2dp, full tab width, square; no divider.
- **Date row**: 48dp chevrons, title titleMedium 16sp centered. The next chevron is dimmed while the period contains today. Labels:
  - Day: "Today", or "Thu, 24 Sept".
  - Week: "This week", or "20–26 Sept".
  - Month: "This month", or "September".
  - Year: "This year", or "2025".
- **Day header**:
  - Value in displayMedium 45sp plus a status in bodyMedium 14sp, on one baseline.
  - Description in bodyMedium 14/20, #BEC9C3.
  - 66dp gauge at the right edge, 24dp from the screen side, with its top 14dp below the header top.
  - Text margins are 24dp.
- **Charts**:
  - **Frame**: 212dp tall. The plot runs from x = 24dp to 312dp, y from 341 to 512dp on screen. Y labels are bodySmall 12sp, left-aligned at x = 325dp.
  - **Line charts**: point i sits at 24 + i·288/n.
  - **Bar charts**: bars are centered in their slots, 16dp wide for ≤ 12 slots, 6dp for 24, 3.5dp for a month.
  - **Y axis**: top = 1.2 × the highest point or band. Middle = half the top, rounded up to a 1/2/5 × 10ⁿ step (139/70, 123/65, 106/55).
  - **Day chart**: top = the server's "very high" bucket (≈ 0.45 × chronic) unless the day exceeds it.
- **Dotted lines**: 2dp round dots every 5dp. Target bands are the accent at 12 %. Logged workouts are #E3E987 at 12 %, with dotted vertical edges.
- **Lines**:
  - Width 1.5dp; the day chart is smoothed (Catmull-Rom, 1/6) and turns yellow inside workouts.
  - Dots are 6dp. A dot gets a 1.5dp background ring only when it is inside its target.
  - The day line ends in a ringed dot.
- **Legend**: centered row with 24dp gaps. Swatch 16 × 10dp: the color at 16 %, inset 1.5 × 2dp, with a 1dp dot at each corner. Then 8dp, then a bodyMedium label in #BEC9C3.
- **Lists**:
  - Header row: period label on the left; "Daily average" on the right for Month and Year.
  - Grouped cards: 68dp tall, 4dp gaps, 16dp margins, 16dp outer and 4dp inner corners.
  - Label 14sp Medium, value headlineMedium 28sp.
- **Workout cards**: 92dp tall. Time in 14sp Medium on the left; on the right the name in 28sp and "Cardio load: N" in bodyMedium.
- **Breakdown bars**: a 4dp #292D2C track under a 10dp mint pill. Hidden when all load came from logged workouts.
- **Training bar**:
  - Six equal segments with 1dp separators: yellow, mint, mint, teal #018673, teal, yellow.
  - Segments cover the ratio ranges 0–0.8, 0.8–0.945, 0.945–1.1, 1.1–1.195, 1.195–1.3 and 1.3–1.89. The marker is linear within its segment.
  - Marker: 12dp, a dark ring around a light center.
- **Section titles**: titleMedium 16sp. "Learn more" and "Adjust my target" links use the accent.
- **Readiness**:
  - Accent lavender.
  - Bars on a 0–100 scale with dotted guides at 30 (sparse) and 65 (dense).
  - Legend "30 - 64 Moderate" (one dot) and "65–100 High" (two dots).
  - "What impacted your readiness" cards: HRV, past week of sleep and RHR.
- **Steps (goal metrics)**:
  - Solid goal line in the accent; bars that reach it carry a check (16dp bars) or a small dot (thin bars).
  - Axis labels: 0, goal/2, goal (in the accent), plus a rounded-up top only when it clears the goal by 15 %.
  - Hourly chart top: two significant figures with an even last digit (3,050 → 3.2k).
