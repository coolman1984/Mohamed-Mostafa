# Pinned Juggluco source investigation

Reference: [j-kaltes/Juggluco](https://github.com/j-kaltes/Juggluco/tree/e41f4aa239d389bee78cdb8fc7d0dbe9ceb4df53)
commit `e41f4aa239d389bee78cdb8fc7d0dbe9ceb4df53` (version message 11.5.4).
Retrieved through the GitHub source connector. Static inspection only: no reference build, APK
execution, sensor capture, decryption experiment or on-phone verification was performed.

All findings below are **SOURCE_CONFIRMED**, **not DEVICE_VERIFIED**. They describe this
reference implementation, not a guarantee about the owner's GX-01S firmware. No reference
implementation, keys, serial numbers or capture samples are copied into SmartCGM.

## Evidence graph

Paths below are relative to `Common/src/` in that pinned commit.

| Domain | Reference behavior | File / function anchor | Remaining cross-check |
|---|---|---|---|
| Discovery / transport | Callback selects service 181f and defines f001, f002, f003 characteristics | `dex/java/tk/glucodata/AidexXGattCallback.java`, UUID constants, `discover`, `getService` | Owner service discovery and advertisement names |
| Pairing | Callback chooses unbonded/bonded notification paths from saved key plus Android bond state | Same file, `onConnectionStateChange`, `discover`, `onDescriptorWrite`, `askKey` | Actual first-pair/reconnect exchanges and wrong input |
| Writes | Key request uses default write type; control writes use no-response with pacing | Same file, `writeCharDefault`, `writeChar`, `writeCharAlg` | Supported properties, timings and failure behavior on this sensor |
| Java/native boundary | f003 notifications go to current-data JNI; f002 notifications go to history/control JNI | Same file, `onCharacteristicChanged`; `main/cpp/aidexx/java.cpp`, corresponding JNI exports | Aligned HCI notification vs reference reading |
| Cipher | Reference defines AES-128-CFB128 helpers, with local IV state copied for helper calls | `main/cpp/aidexx/decrypt.cpp`, `aes_cfb128_decrypt`, `aes_cfb128_encrypt` | Verify outer frame/key selection and real vectors; do not infer all IV boundaries from helper alone |
| Pairing inputs | Serial-derived IV/request material exists; 16-byte saved key and 17-byte key-response checks appear in native flow | `main/cpp/aidexx/mkiv.cpp`, `mkAidexXiv`, `aidexXaskKey`; `java.cpp`, `aidexXSaveKey`, `aidexXstartCommand` | QR structure, authorized ownership and actual key exchange |
| Integrity | Native current/history handlers check trailing CRC16; key response checks CRC8 | `main/cpp/aidexx/crc.cpp`, `crc16_ccitt_false`, `crc8_maxim`; `java.cpp`, current/history/key JNI functions | Independently recorded known-answer vectors and malformed packets |
| Live payload | Reference packed current struct contains trend, minutes since start, glucose/validity fields; current parser expects 15 decrypted payload bytes before CRC | `main/cpp/aidexx/glucose.h`, `CurrentGlucose`; `java.cpp`, `saveCurrent`, `aidexXprocessCurrentData` | Bounds, endian/bit ordering, errors and firmware variants |
| Clock / trend | Timestamp is reconstructed from sensor start plus adjusted minute index; native trend conversion multiplies by 0.1 | `java.cpp`, `id2time`, `AidexTrend2RateOfChange`, `saveGlucose` | Start-time timezone/DST, offset reset and aligned timestamp/value comparison |
| History | Native history request uses a relative cursor; received packed records advance it and may request another page | `java.cpp`, `askHistory`, `whenPresent`, `getPastValues` | Real pagination, duplicates, gaps and termination after 30-min/2-h outage |
| Sensor end / failure | Current handler distinguishes an end-type response; Java disconnects for selected native return states; CRC failures reject data | `java.cpp`, `saveCurrent`, `aidexXprocessCurrentData`; callback current notification branch | Actual firmware end-of-life, disconnects, GATT errors and recovery |

## Characteristic map (reference only)

The standard Bluetooth base UUID suffix is `-0000-1000-8000-00805f9b34fb`.

| Short UUID | Callback role |
|---|---|
| 181f | Service filter |
| f001 | Private unbonded path / key material exchange |
| f002 | Control/history read, writes and notifications |
| f003 | Bonded current-data notifications |
| 2aa9 | Minutes from start |
| 2aaa | Start time |
| 2aab | Runtime |

MTU, fragmentation, complete CCCD implementation (inherited helper), QR field layout and
connection ownership are not established by these six files alone. The reference also contains
write/reset/OTA behavior; none is adopted by this read-only project.

## F005 research gate

No literal `f005` was found, case-insensitively, in the six inspected files below. This is
**not** proof that a variant does not exist elsewhere or in the owner's firmware. Its applicability,
commands, response schema and test vectors remain **UNKNOWN / BLOCKED_PENDING_OWNER_INPUT**.
No F005 command or fallback sequence is enabled.

## License boundary

File headers declare GNU GPL version 3 or later. This report records interface observations
and provenance, without transplanting reference code. Any future code reuse needs a separate
license compatibility assessment and owner decision. Source confirmation alone does not approve
clinical use, bypassing sensor expiry, or activating the direct source.

## Exact source blob identifiers

| Path | Git blob SHA |
|---|---|
| `Common/src/dex/java/tk/glucodata/AidexXGattCallback.java` | `3cce7c032c59118e2477cda6db453b2977f53e32` |
| `Common/src/main/cpp/aidexx/glucose.h` | `6e5a6b524d30c0d0b12594aaf0f13d5806b6cdf3` |
| `Common/src/main/cpp/aidexx/java.cpp` | `4983d374dd32c295c4f44c01b639628725b7692a` |
| `Common/src/main/cpp/aidexx/decrypt.cpp` | `3e9b0ea21da39c0d1db9d2b3fde253dbcc61f777` |
| `Common/src/main/cpp/aidexx/crc.cpp` | `80f829f24be7a4e534ba03c3d3d64566c42c2cc3` |
| `Common/src/main/cpp/aidexx/mkiv.cpp` | `77797c6c94845a94abbf6907c3c738cde67e8dc0` |

