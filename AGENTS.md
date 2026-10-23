# AGENTS.md — Handoff: finish Smart CGM to production

> For the AI agent taking over (Grok or any other). Read this file fully before touching code.
> Goal: the app works **100% on a real phone, from anywhere (any network)**, for a real diabetic patient and their family.

---

## 0. Rules you must follow

### Talking to the owner
- The owner is **not technical**. Reply in **Egyptian Arabic**, short, simple, with emojis. No code and no English jargon.
- Give the bottom line first, then the options and their trade-offs, so the owner can decide.
- **Ask before** anything irreversible or costly: merging to `main`, deleting data, paid services, publishing to the Play Store, changing medical math.

### Git
- Work on branch `ccr-c49cb109-n6uent` (the current code is there; `main` is empty). Ask the owner before merging into `main`.
- Make small commits with clear messages. CI must stay green on every push.

### Medical safety (never break these)
1. The app **never injects** insulin. It only *suggests* a dose, and the person confirms it.
2. Keep these `BolusAdvisor` protections, with their tests:
   - stacking protection (IOB > 0.3 u → no correction);
   - zero dose when glucose is low or a low is predicted;
   - the max-bolus cap;
   - rounding **down** to 0.5 u.
3. Urgent low (< 55) always alarms, ignores snooze, and is critical.
4. Never hide or delay a low alarm to save battery.
5. Any change to `core/` math needs a unit test with numbers worked out by hand.
6. Keep the disclaimer: "assistant, not a medical device".

---

## 1. What exists today (built, CI green)

### Architecture
```
Sensor (LinX/AiDEX X, Libre 2/2+/3/3+, Sibionics GS1)
   │ Bluetooth (proprietary, encrypted)
   ▼
Juggluco (free open-source app, tk.glucodata) ── decodes all 4 sensor families
   │  (a) broadcast "glucodata.Minute"   (b) xDrip "BgEstimate" broadcast
   │  (c) local web server http://127.0.0.1:17580 (Nightscout API)
   ▼                      ┌── fallback (d): read the notification of the official LinX app
Smart CGM (this repo) ◄───┘
   │ core/ math → alerts, logbook, reports, bolus suggestion, AI camera
   ▼
Cloudflare Worker + D1 (backend/) ──► follower phones (parents/doctor)
```

**Why Juggluco:** the sensors' BLE protocols are proprietary and encrypted. Juggluco already
supports all four families and is maintained. Writing our own BLE stack would take months and
would carry clinical risk. **Do not try to write BLE decryption yourself.**

### Repo map
| Path | What it does |
|---|---|
| `core/` | Pure Kotlin/JVM, no Android. All medical math. Build/test alone: `./gradlew -p core test` |
| `core/.../Calibration.kt` | `calibrated = raw*slope + offset`. 1 finger-stick → offset only. 2+ → least squares, slope clamped 0.7–1.3 |
| `core/.../Trend.kt` | Rate of change (mg/dL/min) over the last 15 min → arrow |
| `core/.../Prediction.kt` | Projects 20 min ahead (damping 0.85) → `minutesToLow` |
| `core/.../InsulinMath.kt` | IOB (exponential curve, peak 75 min, DIA 4 h, rapid insulin only), COB (linear) |
| `core/.../BolusAdvisor.kt` | Dose suggestion and the safety rules above |
| `core/.../Stats.kt` | TIR/TBR/TAR, mean, GMI, CV, AGP percentiles per hour |
| `core/.../SensorLife.kt` | Warm-up 60 min; ACTIVE < 15 days; EXTENDED 15–20 days (daily "replace" warning); EXPIRED > 20 days (readings hidden) |
| `core/.../AlertEngine.kt` | Separate low/high rules, repeat interval (5/15/custom), snooze, urgent low, predicted low, signal lost, sensor replace |
| `core/.../Logbook.kt` | Hourly Arabic table (day, DD/MM/YY, "12 ص"), colors, CSV with BOM |
| `core/.../NotificationParser.kt` | Extracts mg/dL or mmol/L from another app's notification text; holds the list of CGM package names |
| `core/.../MedicationEffects.kt` | Offline list of medicines that raise or lower glucose |
| `core/.../Followers.kt` | Roles ADMIN/VIEWER; 16-char account code; invite code `CODE-A` / `CODE-V` |
| `app/.../data/` | `Db` (SQLite, stores RAW values), `SettingsStore` (SharedPreferences), `Repo` (single entry point: `ingest()`, calibrated `readings()`) |
| `app/.../sources/Sources.kt` | Broadcast receiver, notification listener, Juggluco web poller |
| `app/.../engine/` | `MonitorService` (foreground, 60 s loop), `Pipeline` (evaluate alerts, sync), `Alarms` (channels, critical sound on the alarm stream), snooze/boot receivers |
| `app/.../cloud/CloudSync.kt` | Cloudflare Worker client: `/v1/p/{code}/readings`, `/status`, `/events`, `/proposal`, invites; per-device bearer tokens |
| `app/.../ai/VisionAi.kt` | Gemini `generateContent` with an image → JSON (meal carbs, or medicine OCR) |
| `app/.../export/Exporter.kt` | CSV and A4 colored PDF, shared through FileProvider |
| `app/.../ui/` | Compose screens, forced RTL: Home, Scanner, Logbook, Analytics, Settings |
| `.github/workflows/android.yml` | Runs core tests, builds the release APK (debug-signed), uploads the artifact `SmartCGM-apk` |
| `docs/RESEARCH.md` | Research summary (Arabic) with sources |
| `backend/` | Worker (`src/worker.js`), D1 schema (`migrations/`), tests (`npm test`), `wrangler.toml` |

### Build
- JDK 17, Gradle 8.14.3 (wrapper), AGP 8.7.3, Kotlin 2.1.0, compileSdk/targetSdk 35, minSdk 26.
- `core` is an **included build** (`includeBuild("core")`). The app depends on `com.mm.smartcgm:core:1.0`.
- Run `./gradlew -p core test` and `./gradlew :app:assembleRelease`.
- In CI, the APK lands in Actions → "Build APK" → artifact `SmartCGM-apk`.
- Status: the core tests pass (15) and the APK builds.
- **Never tested on a real device yet.** That is your main job.

---

## 2. Task list — do these in order

Each task has a **Definition of Done (DoD)**. Do not mark a task done without meeting it.

### P0 — Make the data actually arrive (nothing else matters without this)

**P0-1 Verify the Juggluco broadcast contract.** *(not verified: the extra names were written from memory)*
- Read the Juggluco source (https://github.com/j-kaltes/Juggluco, look for `glucodata.Minute`, `SendNSClient`, `XInfuus`, `BgEstimate`).
- Confirm the exact action names and extras: keys, types (int vs float vs long), and units.
- Fix `GlucoseBroadcastReceiver` in `app/.../sources/Sources.kt` to match.
- Also check whether Juggluco sends broadcasts with an explicit package (it has a "select apps" list). Implicit broadcasts don't reach manifest receivers on Android 8+, which is why `MonitorService` also registers dynamically. Keep both.
- DoD: a debug screen or log shows readings arriving from Juggluco on a real phone with a real sensor, once per minute, with the correct value (compare with the Juggluco screen, ±1 mg/dL).

**P0-2 Verify the Juggluco web server.**
- Confirm the port (17580) and the path `/api/v1/entries.json`, including whether Juggluco needs "local only" or an API secret.
- Make sure the field names `sgv` / `date` are right.
- DoD: after 30 minutes with broadcasts off, the poller backfills the missing readings.

**P0-3 Verify the official-app notification fallback.**
- Get the real package names and notification text formats for:
  - LinX (`com.microtech.aidexx.mgdl`, confirmed);
  - AiDEX X;
  - Sibionics (`com.sibionics.sibionicscgm` is a guess — verify);
  - Libre 3 regional apps.
- Add real samples as unit tests in `CoreTest.notificationParsing`.
- DoD: with Juggluco uninstalled and the LinX app running, readings arrive every 1–5 minutes.

**P0-4 Add a "Data source health" card** on Home/Settings.
- Show the last reading time per source, which source is active, and a red banner when nothing has arrived for 10 minutes.
- Add a step-by-step setup wizard (Arabic) on first launch:
  1. install Juggluco;
  2. start the sensor in Juggluco (LinX: scan the data-matrix on the box; Libre: NFC; Sibionics: code);
  3. turn on the broadcast to our app;
  4. grant notification access, battery exemption, and Do-Not-Disturb access.
- DoD: a non-technical person can finish setup alone.

**P0-5 Unit display.** Add an mg/dL ↔ mmol/L option across the whole UI, the exports, and the notifications. Store values in mg/dL internally.

### P1 — Reliability "from anywhere"

**P1-1 Background survival.**
- Test on Android 12, 13, 14 and 15, and on Samsung and Xiaomi: kill the app, reboot, doze for 8 h.
- Check that `BOOT_COMPLETED` → `MonitorService` with `specialUse` type is allowed on Android 15; if not, use an allowed type or a WorkManager fallback.
- Add a WorkManager watchdog every 15 min that restarts the service.
- DoD: 24 h unattended run with no gap > 5 min.

**P1-2 Critical alarm hardening.**
- Declare `USE_FULL_SCREEN_INTENT` (Android 14 needs explicit user consent — add a settings button).
- Make the urgent-low sound **repeat until acknowledged**, using a full-screen alarm activity with an "I took sugar" button.
- Persist `AlertState` (snoozes, last fired) in SharedPreferences so a process restart doesn't re-spam or forget snoozes.
- DoD: the phone is on silent + DND, glucose is 50 → loud alarm repeats until tapped.

**P1-3 Remote family alerts that work when the follower app is closed.**
- Today followers poll the Cloudflare backend every 60 s from their own foreground service. That works on the internet, but only while the service is alive.
- (Optional, off for now — no Firebase config in the repo) add push so the patient's phone or a Cloud Function sends high-priority push to ADMIN followers on urgent low, predicted low, and signal lost.
- Options to present to the owner:
  - (a) Cloud Function — needs the Blaze plan, usually still free at this volume;
  - (b) the patient phone sends FCM through the HTTP v1 API — needs a service-account key on the phone, which is **insecure, do not do it**.
  - Recommend (a).
- DoD: a follower phone that is locked and the app killed gets the alarm within 60 s.

**P1-4 Secure sharing — DONE by moving to Cloudflare (backend/): one-time 24 h invites, per-device tokens (hash only on server), server-side roles and proposal limits, urgent-low not proposable.** Old Firebase notes, kept for history: The current rules make the data readable by anyone who knows the 16-char code. Replace them with:
- Firebase Auth (anonymous or Google sign-in) on every phone;
- `/patients/{code}/members/{uid} = "admin"|"viewer"|"owner"`;
- rules where only members read, only the owner and admins write `events`, and only the owner writes `readings`/`status`;
- joining by invite: the follower writes its uid under `pending`, and the owner approves it in the app (shows name + role). This also lets the owner **list and remove followers** (the spec says an unlimited number of followers).
- Move from raw REST to the Firebase Android SDK (needs `google-services.json`; ask the owner to create the Firebase project and give access).
- DoD: a stranger with the code but no approval can't read anything (test it with the Firebase rules emulator).

**P1-5 Pairing UX.**
- Put the DB URL inside the QR/invite (`SMARTCGM:<url>|<code>-<role>`), and add a QR scanner (CameraX + ML Kit barcode, or ZXing) so the follower only scans.
- Keep the patient's own account code when switching to follower mode — today `accountCode` is overwritten. Split it into `ownAccountCode` and `followAccountCode`.
- DoD: a follower joins in under 30 seconds by scanning.

**P1-6 Patient-side offline queue.** If there is no internet, queue the uploads and flush them later. Today `pushLatest` only sends the last 15 minutes, so long outages lose follower history.
- DoD: 2 h in airplane mode → followers see the full curve afterwards.

### P2 — Feature completeness versus the owner's spec

**P2-1 Calibration UI.** Add a list of finger-sticks with delete, show the current slope and offset, and add an "undo calibration" button. Warn when a finger-stick differs from the sensor by > 40%.

**P2-2 Logbook.**
- Add events (insulin, carbs, meds) as extra columns or rows.
- Export a real `.xlsx` (Apache POI is heavy; consider `fastexcel`, or keep the CSV with BOM, which Excel opens fine).
- Check the PDF Arabic shaping and RTL on a real device.

**P2-3 AI camera.**
- Store the Gemini key with `EncryptedSharedPreferences`.
- Validate that the model name exists (call `models.list`).
- Add a "test key" button.
- Let the user edit the AI carbs before the dose calculation.
- Optional: an OpenAI provider switch.
- Never auto-log a dose from AI without confirmation.

**P2-4 Reports.**
- Add an AGP doctor PDF with the chart image: render Compose/Canvas to a Bitmap and draw it in the `PdfDocument`.
- Add daily overlays, plus the 14-day standard (the international consensus uses ≥ 70% of possible readings).

**P2-5 Sensor lifecycle.**
- Detect a new sensor from a serial change (already done) and also from a gap > 2 h followed by warm-up.
- Show the "days left" ring on Home.
- The owner's rule stays: show readings until day 20, with a daily warning from day 15. Tell the owner clearly that most sensors stop by themselves at 14–15 days; the app cannot extend the hardware.

**P2-6 Follower ADMIN edit rights.** ADMIN followers can log doses and carbs (done via `/events`), and possibly edit alert thresholds remotely, which needs a `/settings` node plus owner approval.

### P3 — Release

**P3-1 Signing.** Create a release keystore and store it as GitHub secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`). Sign the release in CI. **Ask the owner first.** Never commit the keystore.

**P3-2 Versioning.** Derive `versionCode` from the CI run number. On a `v*` tag, create a GitHub Release with the APK attached, so the owner has one simple download link.

**P3-3 Quality gates.**
- Add `./gradlew lint`, plus ktlint or detekt, to CI.
- Add Robolectric tests for `Repo.ingest` (dedupe ±30 s, sensor change) and for `GlucoseBroadcastReceiver` (fake intents).
- Keep the core tests at 100% pass.

**P3-4 Privacy.**
- Health data stays on the device and in the owner's own Cloudflare account (D1, readings kept 30 days).
- Write a short Arabic privacy note in the app.
- Keep `allowBackup=false`.

**P3-5 Play Store (optional — ask the owner).** Health apps need a Health declaration and a privacy policy. `specialUse` foreground services need justification. Sideloaded APK is fine for family use.

### Out of scope unless the owner asks
- **iOS.** Juggluco is Android-only, and iOS doesn't allow reading other apps' notifications. An iOS version would need its own BLE stack per sensor (xDrip4iOS has partial work) — a big project. Explain this to the owner and get a decision first.
- Writing our own BLE decoders.
- Embedding Juggluco code. It is GPL-3, so the whole app would have to be GPL-3 too. Ask the owner first.

---

## 3. Acceptance test — "works 100%"
Run this on a real phone with a real LinX sensor, then repeat with a Libre and a Sibionics sensor if available.

1. Fresh install → the wizard completes → the first reading appears in ≤ 5 min.
2. The value matches the official or Juggluco app within ±1 mg/dL, and the arrow matches.
3. Readings keep arriving after a reboot and after 8 h of the screen being off.
4. Finger-stick calibration changes the displayed values as expected.
5. Low alert at 70, repeating every 5 min. High alert separately, every 15 min. Each can be switched off on its own.
6. Urgent low < 55 sounds loudly while the phone is on silent + DND. Snooze works, except for urgent low.
7. A predicted-low alert fires about 15–20 min before crossing 70 when glucose is falling at about 2 mg/dL/min.
8. Bolus suggestion:
   - 60 g at ICR 10 → 6 u;
   - with IOB 2 u and BG 250 → no correction;
   - BG 65 → 0 u.
9. Camera:
   - a meal photo returns carbs, and the dose dialog opens prefilled;
   - a medicine box photo returns the name, and dexamethasone triggers the "raises sugar" warning.
10. The logbook shows hourly rows with the right Arabic day, `DD/MM/YY` date, `12 ص`-style time and colors. The CSV opens correctly in Excel (Arabic is readable). The PDF looks right.
11. Reports show the TIR bar, GMI, CV and AGP. The doctor PDF can be shared.
12. Followers:
    - an ADMIN on another network (mobile data, a different city) sees live readings within 1 min and gets the urgent-low alarm with their app closed;
    - a VIEWER sees the data but gets no alarms and can't edit;
    - the owner can remove a follower.
13. Sensor day 15 → daily "replace" warning. Day 20 → readings hidden and an "expired" message.
14. CI is green and a signed APK is published as a GitHub Release.

When everything passes, send the owner:
- a short Arabic summary (what works, what doesn't, what they need to decide);
- the download link.

---

## 4. Known weak spots in the current code (fix while you're there)
- `SettingsScreen.NumField` commits on every keystroke. The sensor "days ago" field resets the start time while typing. Add a confirm button.
- `CloudSync.pullAsFollower` downloads 1440 readings every minute. Fetch only after the last key you already have (`startAt`).
- `Repo.readings()` re-fits the calibration on every call. Cache it and invalidate when a finger-stick is added.
- `Db` has no pruning or indexes beyond the primary key, and no migrations. Add a schema version plan.
- `NotificationParser` may grab a non-glucose number from unusual texts. Restrict it to the known package formats once you have real samples.
- `Alarms.playCritical` plays once. See P1-2.
- The Gemini model default `gemini-2.5-flash` may change. Make it validated or selectable.
