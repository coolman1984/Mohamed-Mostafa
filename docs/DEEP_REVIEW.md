# Exhaustive code review follow-up

Baseline `a051c50f1fe6a53476097aab225731c730ddc647` on `main`.
Owner explicitly authorized review, repairs and merge to main. Earlier mission's no-merge rule is overridden
for this requested merge only. No production backend deployment, store publishing, signing-key change,
real-device verification, sensor lifecycle bypass or real BLE codec activation is included.

## Verified failure paths repaired

| Area | Before / failure trigger | Repair and evidence |
|---|---|---|
| Account isolation | Patient/follower changes shared SQLite readings, doses and calibration | Separate follower database per backend/account, preserved owner database/lifecycle, no cross-profile response writes; integration tests |
| Legacy mixed data | Historical cloud rows in the owner DB could become owner glucose | Preserved on disk but excluded from owner clinical reads; followers refetch into their account-specific database |
| Event delivery | Delete-before-import could lose an insulin log on lost response/crash | Transactional local receipt plus log before remote ACK; receipt survives process restart and failed ACK |
| Sync cursor | Global cursor reused after backend/account/token change | Namespaced cursor and registration state; reject partial upload acknowledgements; status retry without new data |
| Cloud trust | Legacy tuples/full metadata could make experimental source look trusted | Full metadata client, quarantine experimental/extended rows, legacy server omits unrepresentable rows |
| Sensor chronology | Out-of-order backfill/duplicate/retired serial could change sensor clock | Persist before lifecycle, only advancing samples affect lifecycle, retired serial guard |
| Calibration cache | Finger-stick before data cached identity, later data never invalidated fit | Invalidate after accepted raw sample; independent calibration per profile |
| Dose eligibility | Unknown sensor age, restart suspicion, follower mode could use own therapy | Explicit eligibility blocks, pending restart gates, single dialog checks |
| Invalid input | NaN/Infinity survived numeric clamp; negative IOB/invalid target allowed unsafe advice | Finite settings fallback, invalid-input rejection, localized numeric parser; valid-dose equations unchanged |
| Legacy corrupt logs | Previously stored negative/NaN medical entries contaminated totals/fit | Validation at storage and calculation boundaries; normal formulas unchanged and hand-worked tests retained |
| Sound ownership | Snoozing HIGH or ordinary app resume stopped urgent-low sound | Per-alert sound acknowledgement; urgent requires explicit urgent acknowledgement |
| Reused alarm screen | Single-instance urgent screen retained old message | Render on new intent |
| Clock rollback | Future last-fired timestamp prevented repeating alerts until wall clock caught up | Reset repeat/snooze suppression on detected rollback; regression test |
| Service continuity | Notification failure killed periodic loop; watchdog reported success after rejected start | Isolate notification/tick failures, watchdog retry, evaluate before local HTTP poll |
| Broadcast units | Missing explicit mg/dL guessed a value below 35 was mmol/L | Require verified unit/time extras, finish async broadcast only after ingest |
| Notifications | Battery numbers/low values/age-changing frozen text caused false or missing readings | Bind explicit units, reject ambiguity, support 20–600, remove volatile age/clock fingerprint fields |
| Network bodies | Full unbounded JSON read, malformed shape/large UTF-8 bodies | Bounded reads, object validation, strict route lengths, HTTPS and no credential redirects |
| AI response | Invalid/non-finite nutrition or model path could enter dose UI / leak response details | Model allowlist, bounded response, numeric range validation, generic errors |
| Invite race | Concurrent redeems could both consume same one-time invite | Guarded insert and conditional consume in atomic D1 batch; 12-way regression |
| Owner race | Concurrent claims could both become owner | Conditional atomic insert; concurrent claim regression |
| Report coverage | 9-minute window could report 200% due floor slot count | Ceiling denominator; hand-worked 2/2 slot test |
| Build gate | Lint errors were non-blocking and APK triage tests omitted | Blocking lint, triage test stage, XML test-report artifacts |

D1 batch contract verified against https://developers.cloudflare.com/d1/worker-api/d1-database/#batch
before using transactional batches. SQLite test stand-in executes whole batches without yielding to
simulate the documented transaction boundary; real deployed D1 concurrency is not claimed tested.

## Validation and remaining limits

Backend tests and offline APK-triage tests run locally. Core/app tests, APK build and lint run in GitHub
Actions because local Gradle distribution download is unavailable. See PR checks for final results.
Synthetic data only. No real phone, sensor, DND/Doze endurance, native protocol, actual APK or capture
was available. These remain explicitly blocked, and experimental BLE stays disabled.

This review is source- and test-based; it does not establish absence of all possible bugs or medical
validation. Cloud backend source changes require a separately authorized deployment to affect the live
service; this merge alone changes repository code and build artifacts.
