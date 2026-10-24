# SMARTCGM / LINX GX-01S — EVIDENCE-FIRST REVERSE ENGINEERING & DELIVERY MISSION (v2)

## Role, target, and non-negotiable result
You are the lead Android, Bluetooth Low Energy, native-code analysis, reverse-engineering, safety, and test engineer. Work to produce the **best defensible implementation supported by evidence** for the owner's authorized sensor and application. Reverse engineer only what is necessary for lawful interoperability, not unrelated user data or proprietary features. Never invent protocol details, claim perfect coverage, or declare real-device success without actual device tests.

The target is the LinX CGM 2.6.1 Android application (`com.microtech.aidexx.mgdl`) for the **Microtech LinX GX-01S / AiDEX-X family**, specifically interoperability with its BLE glucose sensor. This is **NOT Sibionics**. Implement an optional native BLE reading source for the owner's SmartCGM app: https://github.com/coolman1984/Mohamed-Mostafa.

**Target repo and Git policy:** Read `AGENTS.md` fully before editing. Start from `grok/production-hardening`, PR #1, create `claude/linx-direct-ble` if absent, and propose a PR **against `grok/production-hardening`**. Its default branch is currently `ccr-c49cb109-n6uent`; do not assume `main` contains code. Never force-push, merge, publish, change production systems, or perform releases without owner's explicit approval. Do not overwrite concurrent changes. Use small, reviewable commits. If a branch name already exists, inspect before updating it; choose a uniquely named branch if it belongs to other work.

**Instruction conflict:** The existing `AGENTS.md` says not to write BLE decryption. This newer, owner-supplied mission explicitly authorizes a separate, experimental direct-BLE interoperability effort. Respect all other `AGENTS.md` safety rules; document that limited override in the PR. Do not alter medical algorithms, insulin calculations, the fixed urgent-low rule, or existing ingestion safeguards.

**Existing app must not regress:** Retain Juggluco broadcasts, its localhost API (`127.0.0.1:17580`), the official-app notification fallback, Arabic RTL user flows, and Cloudflare Worker + D1 sharing (no Firebase). Preserve all existing unit tests and safety alerts. There can be only one active BLE client for this sensor: provide explicit source selection and connection ownership, not competing simultaneous sensor connections.

## Prior observations are hypotheses until verified
- Reference: Juggluco https://github.com/j-kaltes/Juggluco ; prior studied commit `e41f4aa`. Search `AidexXGattCallback.java` and `cpp/aidexx`; verify paths and behavior at a pinned commit.
- Reported advertising prefix: `LinX-<serial>`; reported BLE service: `181f`; reported symmetric mode: AES-128-CFB; reported serial-dependent pairing inputs, 1-minute sensor readings, trend and history backfill. **Verify each claim; do not infer key material from a guess.**
- Reported native LinX library `libblecomm-lib.so`, with pairing, CRC, historical data, and an allegedly newer `F005` pairing variant; verify command bytes, state machine, frame boundaries, error codes, and whether this variant applies to the owner's exact model/firmware.
- Prior claim: LinX application enforces a software cutoff around day 10; physical sensor is marketed for 15 days. **Do not equate absence of an app check with validated 16–20 day medical reliability or the ability to extend hardware life.**
- Prior research may exist at `/workspace/linx-ble-research/REPORT.md`. Reuse only if available and cite what evidence it contains.
- Official APK may contain `overdrive` protection; **do not bypass, defeat, patch, or redistribute proprietary app protections or code**. Do not evade enrollment, licensing, or security checks. Instead rely on lawful static observations, documented API surfaces, open-source reference implementations and approved on-device BLE captures.

## Phase 0 — inventory, environment, and baseline
1. Confirm permission/ownership to inspect the user's APK, phone and sensor. Never touch third-party accounts or devices. Use offline artifacts and user-authorized captures. Do not upload a proprietary APK, packet capture or medical data to public repositories/services.
2. Read the target repo's README, AGENTS.md, Android source, core tests, Gradle configuration, workflows, current PR and exact branch SHAs. Record baseline CI/test status before changes.
3. Inventory actual files supplied: original APK/version/signature/digest, `.so` libraries by ABI, DEX files, resources, sensor model/firmware, box QR/data matrix **with sensitive values redacted**, and capture logs. If artifacts are absent, **do not pretend to analyze them**. Continue with source-code protocol research and a testable scaffold; mark device-dependent claims BLOCKED_PENDING_EVIDENCE.
4. Set up the **official** `morluto/rea` toolchain, verify its **currently installed** CLI/MCP capabilities instead of assuming README-main tools exist in the release. Read REA's Android and native-analysis guides. Android APK analysis can require a separately provided headless JADX engine and full JDK; native `.so` analysis may require a local Ghidra/Hopper/IDA provider. Use `apktool`, `jadx`, `Ghidra` or `Rizin/radare2` as verified fallbacks. Never claim tools ran unless their output exists.
5. Create `docs/linx-re-evidence/manifest.md` with artifact SHA-256 digests, tool versions, command summaries, analysis limitations and provenance. Exclude sensitive raw identifiers and cryptographic secrets.

## Phase 1 — protocol evidence graph (no guessed protocol)
Build a traceable map from every material claim to evidence:

| Domain | Questions that MUST be answered or marked unknown |
|---|---|
| Discovery | name patterns, service UUIDs, manufacturer payloads, serial extraction, connection ownership |
| Transport | all service/characteristic UUIDs, CCCD/notification setup, MTU, BLE write types, timing and fragmentation |
| Activation | QR/data-matrix field structure, pairing inputs, ownership/activation state, how to handle absent QR |
| Security | where key material originates, cipher mode/IV per frame, message auth/CRC, replay counters or sequence handling |
| Pairing | initial commands, legacy flow, `F005` flow, responses, retries, wrong-code handling, timeout states |
| Readings | wire layout, value units, timestamp/clock, trend code, alarm/error flags, temperature/battery if present |
| History | request windows/cursors, pagination, gaps, duplicates, out-of-order messages and pagination termination |
| Sensor state | warm-up, new sensor identity, sensor age, app cutoff vs actual firmware expiry, reset behavior |
| Failure | BLE status codes, disconnects, connection limits, stale packets, corrupt frames, CRC failure, unknown variants |

For each item record **OBSERVED / SOURCE_CONFIRMED / INFERRED / UNKNOWN**, exact artifact digest + file/function/offset or packet index + timestamps, cross-checks, counterexamples and a confidence note. `SOURCE_CONFIRMED` in Juggluco does not mean `DEVICE_VERIFIED` for this user's unit. Use separate flags for actual phone tests.

### REA-centered investigation order
1. Begin with REA `inspect_android_package` / `search_android_classes` / `inspect_android_class` / `inspect_android_method` / `trace_android_references` **only if the connected version advertises them**, using a real provided APK path. Inspect package identity, manifest, native library inventory and reachable class structures. Fall back to locally installed JADX and Apktool as needed.
2. Identify BLE managers, GATT state handling, JNI boundaries, QR parsing and storage, native function exports/imports, crypto and CRC helpers; maintain call graphs **across Java/Kotlin ↔ JNI ↔ `.so`** where supported by evidence. Distinguish static candidates from observed runtime behavior.
3. Analyze `libblecomm-lib.so` with REA's configured native backend, Ghidra or Rizin. Prioritize user-authorized interoperability entry points and frame parsers. Do not copy its decompiled implementation into the application.
4. Independently compare with pinned Juggluco source and optional xDrip+ references. Preserve license attribution and commit identifiers. For each command, document bytes, direction, preconditions, response schema, crypto/CRC conditions, error handling and one redacted sample/test vector where lawfully available.
5. Obtain the owner's **Android Bluetooth HCI snoop** only if required for unresolved questions. Explain simple local capture steps without requiring rooting/unsafe changes; warn that logs can contain sensitive information. Prefer local redaction before sharing. Dissect BLE ATT/GATT events; correlate QR scan, pairing, subscription, live readings, backfill, re-pair, disconnect, and sensor expiry. **Never fake capture output.**
6. If packer/obfuscation blocks app methods, proceed via Juggluco source, accessible native symbols/strings, permitted runtime observations and packet captures. Document the visibility gap; do not bypass protections.
7. `F005` is an explicit research gate: determine whether it is relevant and provide a sample-backed branch of the pairing state machine. If no authentic evidence exists, do **not** invent an F005 implementation or silently fall back to an unverified command sequence. Ship an isolated, disabled-by-default placeholder with a clear BLOCKED flag.

## Phase 2 — clean Kotlin design, isolated from clinical logic
Implement production-quality **read-only** `LinxBleSource` only after each used protocol operation is evidenced. Suggested internal boundaries:
- `LinxScanner` — permissions, filters, serial/model identification, privacy-safe UI labels.
- `LinxGattClient` — one connection, serialized GATT operation queue, notify subscription, MTU negotiation if supported, cancellation and timeout, Android GATT error handling, thorough resource cleanup.
- `LinxPairingCoordinator` — QR/manual inputs, legacy vs verified F005 variant, bounded retries, no hard-coded universal keys, local secure storage of sensitive pairing material when needed.
- `LinxFrameCodec` — independent deterministic encode/decode, AES/IV/CRC checks, fixed bounds, endian correctness, command/response typing.
- `LinxReadingParser` — type-safe glucose value, sensor-relative clock vs phone clock, trend mapping, unit validation, malformed-packet rejection.
- `LinxHistorySync` — pagination/cursor/backfill, timestamp deduplication, ordering, idempotent ingest; do not overwrite newer valid readings.
- `LinxConnectionSupervisor` — explicit reconnect policy, serialized state machine, bounded exponential backoff with jitter, Bluetooth-off recovery, foreground notification and Android-version-specific permissions.
- `LinxSourceAdapter` — no duplicate alerts, reuses existing `Repo.ingest` and the source-health UI, allows source selection and preserves working fallbacks.

Use a **single active sensor source** and show explicit instruction to disconnect Juggluco/LinX before taking BLE ownership. Never silently force-stop another app. Reset only the direct connection, not app data. On failed pairing return to safe fallback, explaining to the user what to do.

Android: respect Android 12+ `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT`, older location requirements, user consent, foreground-service type and Android 14–15 background execution constraints. A foreground service **improves continuity but cannot guarantee Doze survival**. Design reconnection after reboot/process death; measure reliability with real Android devices. Avoid excessive wake locks and battery use.

## Phase 3 — safety and clinical separation
**This is patient-facing experimental interoperability, not an approved medical-device substitute.** Keep existing critical alert threshold **55 mg/dL** immutable to remote changes, insulin safety checks and source fallbacks intact. Do not represent an unverified BLE sample as safe for treatment decisions.

1. Validate mg/dL units, plausible range, CRC/decryption integrity, monotonic time and sensor identity. Reject invalid, duplicate, corrupt, out-of-order or implausibly old values; don't turn error codes into glucose levels.
2. Mark data **stale after >5 minutes** and visibly notify signal loss. Distinguish *last known value* from a live glucose reading; never quietly repeat old values as live or let stale measurements drive dose suggestions.
3. Record source/quality/trust state alongside readings and enforce a clinical safety gate: experimental direct-BLE samples cannot feed bolus advice or be treated as sole protection for life-critical alarms until verified on the intended phone/sensor against an accepted reference and explicitly approved for use. A failed direct source must not suppress normal alerts from healthy existing sources.
4. Keep existing daily sensor-replacement reminder starting day 15 and the owner's request for visibility through day 20 **only as explicit, non-actionable historical/experimental display where data exists**. Day 16–20 values must be conspicuously unvalidated, excluded from insulin suggestions and not presented as clinically reliable; do not bypass hardware expiry or promise any sensor output after the physical 15-day limit. Check with clinician/manufacturer before relying on any extended use.
5. Prominently prompt comparison against finger-stick, particularly when symptoms disagree, readings are surprising, the sensor has aged beyond its specified life, or any source is unverified. Symptoms and urgent treatment require appropriate medical guidance rather than app reassurance.
6. No sensor writes except the minimum, verified connection/pairing/authorized query commands required for read-only access; no firmware modifications, activation resets, lifecycle manipulation or calibration bypass.
7. Preserve privacy: strip pairing material, QR values, serial numbers, patient details, tokens, raw BLE HCI traces and glucose histories from logs, documentation, commits, and public CI artifacts. Use artificial or suitably redacted fixtures.

## Phase 4 — tests before claims
Write deterministic tests and run them at every milestone:
- Codec: published/authorized recorded frames, AES-CFB framing and IV boundary tests, CRC known-answer vectors, fragmented packets, wrong IV, bad CRC, truncated/malformed/oversized fields and fuzz/property tests.
- Pairing: documented legacy path, recorded F005 if available, no-QR path, wrong input, same sensor already connected, notification subscription failure, pairing timeout, cancellation and retries.
- Ingestion: 60-second values and trend, wrong units, clock drift/time zone rollover, duplicated retransmits, out-of-order messages, sensor swaps, reconnect history backfill after 30 minutes and 2 hours.
- Device lifecycle: permission denied, Bluetooth off/on, app background/restart/reboot, Doze, native GATT error 133, connection collision, repeated disconnections, battery usage and no permanent wakelocks.
- Safety: stale after 5 min, urgent-low rule unchanged, no suppression of old fallback sources, day-15 warning/day-16 untrusted behavior, no unverified source into insulin dosing, QR/key privacy.
- UI: Arabic RTL instructions and denial/recovery paths. Tests must not require a real person's glucose readings.
- CI: core tests, app unit tests, Android lint with meaningful failures triaged, debug APK assembly + uploaded workflow artifact, backend tests, no unintended secrets. If Windows support is a project requirement, exercise scripts there too.

**Required real-sensor verification checklist** (owner to perform or observe with permission): record phone model/OS, sensor model/firmware and controlled test duration; verify connection, paired/QR behavior, minute values against currently working reference (timestamp aligned, tolerance explicitly recorded), normal trend, 30-min backfill, disconnect/reconnect, cold reboot, 24-hour endurance, BLE ownership conflict, loss warning, source fallback, and genuine sensor end-of-life response. A recording from one sensor does not prove all firmware variants. Never call a hardware test PASS if the phone/sensor/log were unavailable.

## Phase 5 — documentation, acceptance, and PR
Create or update:
1. `docs/linx-protocol.md`: BLE state chart, service/characteristic table, pairing and F005, per-command schema, AES/CRC evidence, reading/history decode, age and errors; mark each unverified field UNKNOWN.
2. `docs/linx-re-evidence/`: reproducible investigation inventory, tools and versions, artifact SHA-256, source reference commits, redacted command/sample evidence, confidence and gap matrix. Never publish private captures.
3. Kotlin module + Arabic pairing/Settings UI + source health indicator, backed by tests.
4. `docs/linx-test-matrix.md`: test case, level, device/simulator, observed result (PASS/FAIL/BLOCKED), link to non-sensitive evidence and residual risk.
5. Pull request against `grok/production-hardening`, with a **short Egyptian Arabic** summary for the non-technical owner; list implementation, tests, what's not verified, and the exact user phone actions needed. Add a GPL-3 assessment: do not copy Juggluco code verbatim without permission and license review. Credit Juggluco and preserve provenance of findings.

**Definition of done, truthfully applied:**
- `STATIC_ANALYSIS_COMPLETE` only when the report maps required behaviors or explicitly documents unavailable evidence.
- `IMPLEMENTATION_COMPLETE` only when Kotlin code compiles, relevant tests pass, and existing fallbacks and clinical guards are untouched.
- `LAB_VERIFIED` only with reproducible approved frames/captures and a complete test matrix.
- `REAL_DEVICE_VERIFIED` only after the stated on-phone/sensor tests actually pass.
- `READY_FOR_OWNER_REVIEW` only after CI green, debug artifact produced and PR open; never equate this state to validated medical use.
- If missing APK, QR sample or HCI capture prevents a capability, mark it `BLOCKED_PENDING_OWNER_INPUT`, build/test all evidence-supported pieces, and ask for only the smallest missing artifact. Do not claim 100% success while a gate is blocked.

## Working discipline
Work autonomously and iteratively in bounded phases. At each phase emit a compact evidence-backed progress table: attempted, verified, unknown, blocked, next smallest experiment. Fix tool errors or use supported fallbacks instead of repeating the same failed command. Do not invent unavailable APIs, tool results, cryptographic keys or device behavior. Never silently change patient-critical medical calculations, sensor-lifespan enforcement or live production deployments. No merges, releases, paid services, external messages, or Play Store publishing. Stop at a reviewable PR.

## Quickstart for agent
Read this entire mission, then read the repo's `AGENTS.md`. Confirm branch/PR state and what artifacts are **actually accessible**. Set up and test REA's Android/native providers. Make an evidence matrix and baseline test report. Begin source-backed protocol investigation. Implement **only evidence-backed** operations with safety gates and keep the original reading sources alive. Open the review PR with an accurate PASS/BLOCKED matrix and concise Arabic owner summary.
