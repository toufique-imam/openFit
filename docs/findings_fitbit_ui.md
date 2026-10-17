# Historical dashboard measurements

These notes describe an earlier reference implementation. Current openFit uses its own navy, blue,
teal and apricot palette, the Android system sans-serif font and an original rising-bars launcher mark.
Reference dimensions below are historical observations, not the current design specification.

## Current implementation decisions (17 October 2026)

Customize Today, bottom navigation, Health metrics, Mindful days, target-range bands and metric
detail screens are implemented. Body stress has its own meaning and explanatory text. Use Material
icons and openFit's header. Keep a completed goal ring capped at one lap with a check; do not copy
second-lap rendering. No quick-add health-record FAB is planned while openFit remains read-only.
Fresh comparisons of the other focus presets and large single-metric ring remain a device task.
See `product-decisions.md`; the historical observations below are not a list of missing features.

## How the numbers were measured

The retained measurements describe screenshots of a 360dp-wide reference phone:

- Pixel measurements of screenshots from the live app on a 360dp-wide phone (480 dpi, 3 px/dp).

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
   - **Today:** `<  Today  [pencil]`. The pencil customizes the dashboard; openFit also supports it.
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
- **Target band (implemented).**
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
  2dp gap. openFit deliberately keeps its completed ring capped at one lap.
- **Segmented ring** (Exercise days): 66 / 6, one segment per goal day.
  - Gap between segments is 0.06 × 360° = 21.6° along the centerline, which matches the screenshots.
  - The segment shape was approximated from screenshots.
- **154dp gauge** (when the primary metric isn't the dual ring): stroke 10, icon 64. Value in
  displayMedium 45 with headlineMedium 28 for units.

## Tiles (sections)

- Title in titleSmall 14/20 w500.
- Value in displaySmall 36/44, with the unit at 22sp (about 0.611 em).
- Subtitle in bodyMedium 14/20, onSurfaceVariant.
- Gaps: title→value 8, value→subtitle 4. Gauge 66dp on the right.
- Empty values read "No data" or "No score" at 22sp, with a filled circular badge instead of a ring.

## Historical differences and current disposition

- **Icons and header:** use openFit's own choices and Material icons.
- **Tiles:** Health metrics and Mindful days are implemented. Body stress is a separate local estimate.
- **Chrome:** bottom navigation and customization are implemented. A health-record quick-add FAB and
  proprietary header elements are omitted. Sticky date positioning is optional future polish.
- **Rendering:** target-range bands are implemented. Second-lap rendering is deliberately omitted.

## Metric detail screens (Cardio Load, Readiness, Steps)

Shared frame (`ui/detail/DetailScaffold.kt`); historical measurements cross-checked on screenshots.

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

## Health metrics (rechecked on the phone, 16 Oct 2026)

Fitbit's Health metrics is two levels, and openFit now matches both:

- **Day page** ("Health metrics"):
  - Day navigator; "3 of 3" over "In personal range" with a segmented gauge.
  - Then all five metrics in Fitbit's order, each as a grouped row: "Breathing rate (BR) 17 brpm", "Blood oxygen (SpO2) No data", "Resting heart rate (RHR) 61 bpm", "Heart rate variability (HRV) 54 ms", "Skin temp variation".
  - Values are whole numbers.
- **Metric page** (Week / Month / Year):
  - "17 brpm (avg)" over "All in personal range" or "Out of range N times".
  - A line through the daily points over the personal-range band. Points sit at the left edge of each slot, and the band runs to the right edge.
  - Legend: "Personal range · In range" / "Out of range".
  - "About breathing rate" cut to three lines, with Learn more.
  - A list: "This week | Breathing rate (avg)" with days ("Today", "Yesterday", "14 Oct"), weeks ("This week", "4-10 Oct") or months ("This month", "September", plus "Out of range 2 times").
  - A disclaimer at the end.
  - With no data: "No data" over "Blood oxygen unavailable" and an empty 90/95/100 chart.
- **Today tile summary:** a check beside "BR, RHR, HRV" over "In personal range".
- **Resting heart rate** is one value a day (the latest record), not an average of every record.
- **Known difference:** Health Connect has skin temperature that the old Fitbit app shows as "No data", so openFit counts 4 of 4 where Fitbit counts 3 of 3.
- **Personal range width:** Fitbit's HRV band this week was about 41-78 ms against openFit's +/- 2 SD of 47-75. Breathing rate is closer (15.5-19.0 against 15.8-18.9). It is not a simple wider multiplier, so the rule is unchanged.

## Recheck against the phone (16 Oct 2026)

- **Today:**
  - The day's last workout sits under Exercise days as one grouped card ("Walk · 20:07 · 0.57 mi · 17 min").
  - Today's letter in the week bars is bold.
  - Days read Today / Yesterday.
  - Stress and mindfulness comes between Health and Nutrition, with Mindful days. Fitbit's Stress Management Score needs its own servers, so openFit leaves it out.
  - The heart range reads "49-188 BPM"; a live reading reads "72 bpm".
- **Active Zone Min (Week):**
  - "Zone min per day (avg)" and the axis 0 / goal / half / top.
  - Days under the goal draw as short pills.
  - A "Weekly target" section with the AHA line and "241% · 362 of 150 target minutes · This week".
  - The list reads Today / Yesterday with ticks on goal days.
  - Not built: Fitbit's "Time in zones" chip.
- **Readiness:** resting heart rate is graded like HRV ("About usual"), not in bpm.
- **Cardio Load:** "You quickly increased your cardio load. Be careful – rapid increases can lead to overtraining." With no target, Fitbit still scales the day chart from recent load (132 / 70 / 0).
- **Sleep:** stage minutes now match Fitbit's timeline (Awake 1 h 4 m, REM 1 h 56 m, Light 4 h 42 m, Deep 1 h 24 m, against Fitbit's 1 h 3 m, 1 h 55 m, 4 h 42 m and 1 h 24 m).
- **Fitbit-only, not reproducible:** live heart rate between syncs, the watch battery chip, and targets after 25 Sept (Fitbit's servers stopped sending them).

