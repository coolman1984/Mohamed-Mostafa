# LinX direct-BLE — test matrix

PASS = automated test green in CI · BLOCKED = needs owner device/artifact · N/A = not yet implemented.

| # | Case | Level | Where | Result | Evidence |
|---|---|---|---|---|---|
| 1 | Experimental readings excluded from dosing | unit | `LinxGateTest.dosingExcludesExperimental` | PASS | core test |
| 2 | Alarm prefers fresh trusted over experimental | unit | `LinxGateTest.alarmPrefersTrusted*` | PASS | core test |
| 3 | Experimental used for alarm only when no fresh trusted | unit | `LinxGateTest.alarmUsesExperimentalOnlyWhenNoFreshTrusted` | PASS | core test |
| 4 | Experimental may only raise (never suppress) a low | unit | `LinxGateTest.experimental*` | PASS | core test |
| 5 | Validator rejects out-of-range / NaN / future / stale-old | unit | `LinxGateTest.validatorRejects*` | PASS | core test |
| 6 | Validator rejects duplicate / out-of-order | unit | `LinxGateTest.validatorRejectsDuplicate*` | PASS | core test |
| 7 | Stale after 5 min | unit | `LinxGateTest.staleAfterFiveMinutes` | PASS | core test |
| 8 | Trust survives DB reload (derived from source) | unit | `ReadingTrust.of` + `Db` uses `withDerivedTrust()` | PASS | core test + code |
| 9 | Codec refuses to guess (ProtocolNotEvidenced) | unit | `LinxBleSource.onFrame` → "codec-unavailable" | PASS | app unit (Robolectric-free) |
| 10 | Frame decode (AES/CRC known-answer vectors) | unit | — | BLOCKED | needs authorized recorded frames |
| 11 | Pairing legacy / F005 flows | unit | — | BLOCKED | needs recorded pairing exchange |
| 12 | Scan + connect + subscribe on real phone | device | — | BLOCKED | owner phone + sensor |
| 13 | Minute values vs working reference (±tolerance) | device | — | BLOCKED | owner capture |
| 14 | 30-min & 2-h reconnect backfill | device | — | BLOCKED | owner capture |
| 15 | Reboot / Doze / GATT 133 recovery | device | — | BLOCKED | owner devices (A12–A15) |
| 16 | BLE ownership conflict (Juggluco/LinX running) | device | — | BLOCKED | owner devices |
| 17 | Sensor end-of-life behaviour (day 15) | device | — | BLOCKED | owner long run |

## Smallest missing artifacts to unblock (any one helps)
1. A **redacted** Bluetooth HCI snoop log of one activation + a few minutes of live readings (values removed).
2. The **structure** of the box data-matrix (field layout only, actual numbers removed).
3. Phone model + Android version + sensor firmware string.

Until these exist the direct-BLE source stays off and Juggluco remains the real path.
