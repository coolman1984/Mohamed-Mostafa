# LinX RE — evidence manifest

Records artifacts, tools and provenance for the direct-BLE investigation. **No secrets or raw identifiers here.**

## Artifacts analysed
| Artifact | SHA-256 | Notes |
|---|---|---|
| (none) | — | No APK, QR, `.so`, DEX or HCI capture has been supplied to this analysis. |

Device-dependent claims are therefore marked **BLOCKED_PENDING_OWNER_INPUT**. See `../linx-protocol.md`.

## Tools in this repo
| Tool | Purpose | Notes |
|---|---|---|
| `tools/linx-re/apk_triage.py` | Offline, stdlib-only static inventory of a **lawfully provided** APK: digests, native ABIs, DEX, keyword locations. | Does not decompile, decrypt, bypass protection, or print secrets. Owner-supplied. |
| `tools/linx-re/tests/test_apk_triage.py` | Smoke tests for archive handling + privacy. | `python3 -I -m unittest discover -s tools/linx-re/tests` |

For real decompilation/native analysis use the official [REA](https://github.com/morluto/rea) toolchain (headless
JADX + a native backend such as Ghidra/Rizin) on the **owner's own** APK, locally. Never upload the proprietary
APK, captures, or decompiled/native code to any public service or repo.

## Reference (study only, do not copy code)
- Juggluco — https://github.com/j-kaltes/Juggluco (GPL-3). Pin the exact commit you study and cite file/function.
- xDrip4iOS MicroTech/LinX discussion — background only.

## Provenance rule
Every protocol claim added to `linx-protocol.md` must cite: artifact digest + file/function/offset or packet index,
a cross-check, and a confidence flag. A guess is not evidence.

## Pinned source investigation

Six Juggluco source files at `e41f4aa239d389bee78cdb8fc7d0dbe9ceb4df53` were inspected.
See `juggluco-source-map.md` for functions, claims, counter-checks and the F005 visibility gap.
This is SOURCE_CONFIRMED reference behavior only, not a device result. No raw artifacts or reference
implementation have been committed. The real APK/native-analysis providers were not run because
no authorized APK was available.
