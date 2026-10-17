# Decisions for the TODO pass

17 October 2026.

- Keep the optional Google Health source while production verification is pending, as requested.
  Publisher identity, privacy contact and policy hosting are deferred by the owner.
- Defer the shared Git-history rewrite, as explicitly requested by the owner. Keep the working-tree
  document cleanup; leave existing commit IDs and remote refs unchanged.
- Keep energy totals based on recorded intervals. Filling unworn gaps would introduce an additional
  estimate and mix it with measured records. The energy page explains that partial-day totals can differ.
- Keep section tabs as full section views. Hiding a card customizes Today only; the existing Customize
  explanation makes that scope explicit. This preserves access to a hidden card’s history and goals.
- Omit profile photo and height rows for now. A photo does not support a current feature; the existing
  formulas use recorded body measurements without a manually entered height. Avoid collecting unused details.
- Retain screen snapshots. Room holds raw inputs; snapshots save expensive derived states and permit
  immediate display while those inputs are processed again. Serialization tests now cover Today and all
  detail states, including a legacy Today row. Removing this layer needs a measured startup comparison.
- Use compressed BLOB payloads for larger raw record days, rather than one SQL row per sample. This
  preserves every existing record field and keeps whole-day reads efficient. Existing JSON remains readable;
  background sync compacts old rows in batches. A synthetic 1,440-record test requires at least a 4× reduction.
- Derived day-load and six-minute slot caches now use Room. Their old preference values are rebuilt from
  the saved raw copy; they do not cause new Health Connect reads. Unknown database versions have no
  destructive fallback, because the local copy can outlive its source data.
- Follow the system light/dark setting. Each composition has its own palette, and charts capture that
  palette before drawing. Both palettes have automated text-contrast checks.

- Use Training load for the proposed strain feature; a second effort score would duplicate the current
  inputs and purpose. Sleep planning and a timed breathing session are now available from the respective
  Sleep and Body stress menus. HRV is shown as separate context on Body stress; no unvalidated weighting
  has been added to the existing score.
- Retain half weight for estimated health-age inputs as an explicit uncertainty heuristic, alongside
  the renamed Health age label. It is not a clinically established conversion; measured inputs remain
  preferable. This review does not replace the outstanding source and calibration checks.

- Re-file saved records locally when the system time zone changes, then rebuild totals and derived
  caches. Record timestamps are preserved, empty-day markers are moved and no remote read is needed.
  The activity cancels active imports and recreates its models. The first upgrade records the current
  zone because legacy rows did not retain their filing zone; an earlier unknown zone cannot be recovered.
- Preserve the existing 365-day first import window, followed by history up to two years. The original
  TODO's 180-day wording was stale; onboarding and documentation now match the implementation.
- Keep the current goals capped at a full ring with a completion check. A second-lap effect does not
  add useful goal information. Keep Material icons and the openFit header instead of reproducing
  another app's proprietary chrome. Existing Health metrics, mindfulness, section tabs, Customize,
  target bands and detail pages cover the useful historical UI gaps. A separate quick-add FAB is
  deferred until openFit supports writing health records; existing profile/journal actions have routes.

Independent model/source validation and fresh reference-device comparisons remain open.
