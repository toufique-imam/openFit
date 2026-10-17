# openFit documentation

openFit is a local Android health dashboard. Start with the [project README](../README.md)
for data flow, building and validation.

| Document | Purpose |
| --- | --- |
| [Release checklist](release-checklist.md) | Permission justifications, public policy and account prerequisites |
| [TODO validation](todo-progress.md) | Current regression coverage, commands and remaining device checks |
| [Product decisions](product-decisions.md) | Storage, UI, theme and model decisions from this pass |
| [Model provenance](model-provenance.md) | Source review status, formulas and design decisions |
| [Algorithms](findings_algorithms.md) | Model assumptions and historical calibration results |
| [Data sources](findings_data_sources.md) | Health Connect, Google Health and local data behaviour |
| [Dashboard measurements](findings_fitbit_ui.md) | Historical measurements; current identity differs |
| [Recovery models](findings_pulse_features.md) | Reserve, stress and health-age calibration notes |
| [Journal](findings_journal.md) | Behaviour check-ins and associations |
| [Review fixes](review-fixes.md) | Earlier fixes and their validation evidence |

The current metric labels are Training load, Recovery score, Effort minutes, Vitality reserve,
Health age and Body stress. Internal serialized names remain compatible with existing installations.
Historical comparison names in research notes identify reference measurements, not openFit products.
