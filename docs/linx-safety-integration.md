# LinX scaffold: clinical integration follow-up

## Scope and baseline

Base: `claude/linx-direct-ble` at `34d4ce4a0867269191668b1e69dea8e60851430a`, on top of
`grok/production-hardening` at `75c6e17f39da3c6253be8ae2ad92234d908652b1`.
The original scaffold's helpers were not connected to dose dialogs or alarm evaluation; its
shared reading table and legacy cloud tuples could also erase experimental provenance.

The owner-supplied MASTER_MISSION authorizes this isolated experimental BLE effort and its
safety gates, overriding only the older AGENTS.md prohibition on that effort. No BLE wire
operations, medical equations, urgent-low threshold, sensor reset or production deployment
are added here. All real protocol fields remain evidence-gated.

## Resulting behavior

- Database v3 quarantines experimental samples in `experimental_readings`. The upgrade relocates
  recognized legacy LinX samples transactionally and preserves values. Trusted and experimental
  samples at the same timestamp cannot deduplicate each other.
- Source aliases (`linx-ble`, `linx_ble`, case variants and suffixed names) stay experimental.
  An explicit experimental flag cannot be promoted merely by deriving trust again.
- Experimental ingest does not change sensor age/identity, calibration, trusted source health,
  alerts or cloud upload. Existing cloud tuple transport receives only the trusted timeline.
  Experimental samples remain visible in a separate Arabic card labelled non-actionable.
- Both the home and camera dose actions use the same dialog and repository eligibility gate.
  No dose suggestion is shown without a trusted sample no older than five minutes, or during
  warmup/extended/expired known sensor phases. Confirmation rechecks eligibility and the proposal.
  BolusAdvisor equations, stacking cap, rounding and low protections are unchanged.
- The actual alarm pipeline filters experimental inputs before forecast/evaluation. It retains
  the existing trusted alarm timeout/urgent-low behavior. The on-screen source-health warning
  starts after five minutes. An experiment cannot conceal loss of the trusted source.
- The frame boundary requires an evidence-backed absolute sample time and sensor identity;
  packet arrival time cannot make a replay or an unmapped sensor clock look live. Pairing and
  the real decoder remain unavailable. Historical backfill requires its own future verified path.

## Verification

New regression tests exercise real repository/storage migration, calibration, normal/experimental
collisions in both orders, dose eligibility, cloud payload isolation, alarm input selection,
non-finite values, trust aliases, unmapped clocks and replay rejection. No private sensor data
is used. CI results and any failures are reported in the PR; no hardware test is claimed.

Local execution: backend tests 11/11 and APK inventory tests 3/3 passed. Local Gradle bootstrap
was blocked by unavailable network access to the distribution; Android/core validation uses
GitHub Actions. The baseline branch had green core/app/build/backend jobs.

## Remaining gates

No authorized APK/QR layout/HCI capture or real phone/sensor is present in this checkout.
Protocol decoding, pairing, real GATT connection, history, endurance and reference comparison
remain BLOCKED_PENDING_OWNER_INPUT. Existing cloud tuples that may already have lost their
source metadata cannot be retrospectively classified by this change. No cloud deployment or
patient-data migration is performed.
