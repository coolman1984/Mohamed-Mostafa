# LinX GX-01S direct-BLE protocol — evidence map

> Status legend: **OBSERVED** (seen on the owner's own device capture) · **SOURCE_CONFIRMED** (in open-source
> reference, e.g. Juggluco, but not verified on this unit) · **INFERRED** (reasoned, not confirmed) · **UNKNOWN**.
> `SOURCE_CONFIRMED` is NOT `DEVICE_VERIFIED`. Nothing below may drive treatment until `REAL_DEVICE_VERIFIED`.

## Current overall status: **BLOCKED_PENDING_OWNER_INPUT**

No APK, no QR/data-matrix sample, and no Bluetooth HCI snoop log have been provided to this analysis, and no
on-device capture has been run. The pinned reference study now confirms several source-level behaviors (see `linx-re-evidence/juggluco-source-map.md`), but no field is verified on the owner sensor. The shipped
codec/pairing refuse to run (`ProtocolNotEvidenced`). This document is the frame to be filled by the owner-run
investigation; it does not assert a protocol.

What is built and testable today (no device needed):
- `core/BleReadingValidator` — range/units/clock/dedup gate for any decoded reading (tested).
- `core/ClinicalGate` — keeps experimental readings out of clinical consumers; integrated via quarantine + dose/alarm gates.
- `sources/linx/*` — the read-only source boundary, disabled until a verified codec is wired in.

## Device evidence table (reference findings are documented separately)

| Domain | Claim from prior reports (UNVERIFIED) | Status here | Needed to confirm |
|---|---|---|---|
| Discovery | advertises `LinX-<serial>` | UNKNOWN | HCI scan log |
| Transport | GATT service `181f`; notify characteristic; MTU | UNKNOWN | HCI snoop of subscription |
| Activation | key input derived from box data-matrix | UNKNOWN | redacted QR structure (values removed) |
| Security | AES-128-CFB, per-frame IV | UNKNOWN | reference study + capture cross-check |
| Pairing | LEGACY flow; possible `F005` variant | UNKNOWN | recorded pairing exchange |
| Readings | 1-min cadence, mg/dL, trend code | UNKNOWN | aligned capture vs a working reference |
| History | backfill window/cursor | UNKNOWN | capture of a reconnect after a gap |
| Sensor state | app cutoff ~day 10; firmware ~day 15 | UNKNOWN | long-run capture to end of life |
| Failure | GATT 133, stale/corrupt frames | UNKNOWN | capture of disconnects |

## Hard rules for whoever fills this in
- Reverse engineer ONLY what is needed for the owner to read their OWN sensor (lawful interoperability).
- Do NOT bypass, patch or redistribute any app protection, licensing or enrolment check.
- Do NOT copy Juggluco's decompiled/native code into the app; study it, cite the pinned commit, respect GPL-3
  (a verbatim copy would force the whole app to GPL-3 — get an explicit owner decision first).
- Do NOT publish the APK, QR values, serial numbers, keys, HCI traces or glucose history. Redact before sharing.
- Every decoded value passes `BleReadingValidator` and is tagged `EXPERIMENTAL` until `REAL_DEVICE_VERIFIED`.
