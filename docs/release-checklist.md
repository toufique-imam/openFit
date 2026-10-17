# Release preparation

Prepared 17 October 2026. Account submissions and publication are pending.

## Privacy policy

Owner direction on 17 October: skip publisher/contact/hosting work for now. Keep the optional Google
Health source while production OAuth verification remains pending.

The full policy is available from Profile, onboarding and Health Connect’s permission rationale.
Its canonical in-app text is `privacy_policy` in `app/src/main/res/values/strings.xml`.
It covers local records, optional Google Health authorization/network requests, revocation, retention,
Delete everything and retained non-health quota counters. No openFit account or analytics are used.

Before release, add the publisher’s identity and privacy contact, publish the same policy at a public
URL, and enter it in the store listing. No public URL or publisher contact has been invented.
Google requires the policy used for Health Connect to match the published policy:
[Health Connect publishing requirements](https://developer.android.com/health-and-fitness/health-connect/publish).

## Health Connect declaration draft

openFit reads the following types; it requests no health write permissions.

| Manifest read permission | User-visible purpose |
| --- | --- |
| `READ_STEPS` | Daily steps, movement minutes and activity charts |
| `READ_DISTANCE` | Daily distance and workout distance |
| `READ_FLOORS_CLIMBED` | Floors and goal progress |
| `READ_TOTAL_CALORIES_BURNED` | Recorded energy totals and workout energy |
| `READ_HEART_RATE` | Heart charts, effort minutes, training load, body stress and reserve estimates |
| `READ_RESTING_HEART_RATE` | Resting-rate trends, heart-rate reserve, recovery and VO2 estimates |
| `READ_SLEEP` | Sleep duration/stages, sleep score, recovery and sleep regularity |
| `READ_NUTRITION` | Logged food energy and nutrition goals |
| `READ_HYDRATION` | Logged water and hydration goals |
| `READ_HEART_RATE_VARIABILITY` | HRV trends, personal ranges and recovery score |
| `READ_OXYGEN_SATURATION` | Oxygen readings and personal ranges |
| `READ_RESPIRATORY_RATE` | Breathing-rate readings and personal ranges |
| `READ_SKIN_TEMPERATURE` | Temperature variation and personal ranges |
| `READ_VO2_MAX` | Measured fitness readings in preference to estimates |
| `READ_EXERCISE` | Workout history, exercise days and effort breakdowns |
| `READ_WEIGHT` | Weight history and goals |
| `READ_BODY_FAT` | Body composition and health-age inputs |
| `READ_MINDFULNESS` | Mindfulness sessions and weekly goal progress |
| `READ_HEALTH_DATA_HISTORY` | Copy older records for long-term charts and personal baselines |
| `READ_HEALTH_DATA_IN_BACKGROUND` | Refresh the saved copy and resume imports while the app is closed |

Submit only the types actually used by the release. Users may grant a subset. Verify declaration
approval in the actual Play Console; this document is a draft, not a submitted declaration.

## Optional Google Health source

The existing read-only scopes are activity/fitness, health metrics/measurements, sleep and nutrition.
Cross-check `GoogleHealthAuth.SCOPES` with the
[official scope list](https://developers.google.com/health/scopes). Complete the relevant consent-screen
verification and any required assessment using the owner’s Cloud project:
[restricted-scope verification](https://developers.google.com/identity/protocols/oauth2/production-readiness/restricted-scope-verification).
Keep the optional source while verification is pending, as requested by the owner. A successful debug
sign-in does not establish production verification; the verification checklist remains open.

## Package, signing and compatibility

The existing package remains `com.openfit`, version code 1, version name 0.1.0. The owner must confirm
that identifier before the first upload. Do not replace a published signing key or generate an upload
key without an agreed storage and recovery process. Signed release upload remains blocked on credentials.

Release signing can use `OPENFIT_KEYSTORE_PATH`, `OPENFIT_STORE_PASSWORD`, `OPENFIT_KEY_ALIAS` and
`OPENFIT_KEY_PASSWORD` from the build environment. All four are required together; none is committed
or printed. With all four unset, the release artifact remains unsigned.

R8 and resource shrinking are enabled with the optimized Android defaults and dependency consumer
rules. An unsigned optimized build can be checked locally. A signed build still needs a real-device
smoke check of Room reads/migrations, serializable snapshots, Google sign-in and Health Connect records.

The app currently compiles with SDK 37 and targets 36. On 17 October 2026, the official policy requires
API 36 for new phone apps and updates, so the target already meets that requirement. Check the
[current Play target API policy](https://support.google.com/googleplay/android-developer/answer/11926878)
against the intended submission date. Test Android 13’s standalone Health Connect and Android 14+
system Health Connect separately. Build success alone does not establish device compatibility.
