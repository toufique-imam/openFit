# Journal (replaces Fitbit's Coach tab)

Fitbit's Coach tab is a Premium, server-backed workout library, so openFit leaves it out. The middle tab is a journal
instead, modelled on pulse-mobile's (spec only, reimplemented in Kotlin; see the pulse-mobile caveats in the README).

## What it does

- **Check-in:** one yes/no question per behaviour the user chose. Tapping the selected answer again clears it, and the
  day then counts as unanswered for that behaviour.
- **Nothing is asked by default.** The tab opens on "Choose behaviours" until the user ticks some.
- **Behaviours:** 26 built-in ones in three sections (Daytime, Nighttime, Status), plus the user's own. Unticking keeps
  past answers, which still count in insights.
- **Storage:** `JournalStore` (SharedPreferences `journal`): `asked` (keys), `custom` (labels), and one `day:YYYY-MM-DD`
  entry per day such as `alcohol=1,late_meal=0`. Keys are stored with answers, so never rename one.

## Insights (`JournalInsights.kt`)

- An answer on day D pairs with day D + 1: the next morning's Readiness and HRV, and the Sleep Score of the night that
  ends on D + 1 (nights are filed under the day they end).
- Over the last 90 days: difference = mean(yes) − mean(no), with a 70% interval from Welch's t-test.
- The t quantile (0.85) uses the Cornish–Fisher expansion, within 0.01 of the exact value from 3 degrees of freedom up.
- Labels: Positive or Negative when the whole interval is above or below zero, "No clear effect" when it crosses zero,
  and "Needs N yes and N no days" with fewer than 5 of either.
- Limits: an association, not a cause; nothing is adjusted for. A 70% interval is loose on purpose, so about 3 in 10
  behaviours with no real effect will still look clear by chance.

## Ideas not done

- A follow-up amount (drinks, caffeine time) as pulse has.
- Showing HRV in standard deviations from baseline rather than milliseconds.
