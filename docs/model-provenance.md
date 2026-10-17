# Model and design review

Reviewed 17 October 2026. This is an engineering record, not a legal clearance.

## Current naming and design

Training load, Recovery score, Effort minutes, Vitality reserve, Health age and Body stress replace
earlier product labels. Dashboard presets and explanatory copy were rewritten. Body stress explicitly
uses higher values for more physiological stress. Training trend labels describe recorded load
changes instead of asserting an overtraining diagnosis.

The current identity uses navy surfaces, blue primary accents, teal and apricot supporting accents,
Android system sans-serif typography and an original rising-bars launcher mark. The app retains
common dashboard cards, charts and progress rings. The bundled Google Sans font is no longer used.
Original launcher and store artwork are prepared in `docs/store/`. An overall visual release review
and store screenshots remain outstanding.

## Source review remains open

Renaming and rewording do not establish independent implementation. Earlier notes reference
[`pulse-mobile`](https://github.com/adityaongit/pulse-mobile), whose
[declared licence](https://github.com/adityaongit/pulse-mobile/blob/main/LICENSE) is PolyForm
Noncommercial 1.0.0 (checked 17 October 2026). A comparison of complete source history and
per-file provenance has not been performed. Do not mark the source-independence TODO complete.

`domain/CardioTargets.kt` also explicitly records an unverified origin for its training thresholds
and bar segments. That disclosure is retained in the source rather than silently relabelled as a
published formula. These constants need an independently documented basis before source clearance.

Published-study references already recorded in the implementation include Uth (2004) for the
heart-rate VO2 estimate, Kaminsky (2015) for FRIEND percentiles, Phillips (2017) for sleep regularity,
and the dose-response studies listed in `domain/PulseAge.kt`. Each numerical curve still needs
checking against its original publication. Reserve rates and stress scaling are empirical settings,
not published physiological formulas. Estimated age inputs use half weight as a heuristic; their
sensitivity still needs independent validation; the engineering decision to retain that uncertainty
heuristic is recorded in `product-decisions.md`.

Historical RMS figures remain in the research notes with their sample dates. They have not been
rerun against new nights or device data during this TODO pass.

## Repository history

The navigation document identified in the original cleanup item was removed from the working tree,
and references to it were removed from the retained documents. Older commits still contain it.
A history rewrite changes commit IDs and affects other clones. The owner explicitly deferred it on
17 October 2026; no history rewrite has been performed.
Before rewriting, the owner must confirm the branches/tags/remotes in scope and approve a backup
and coordinated replacement of affected refs. No force push is part of the current code changes.
