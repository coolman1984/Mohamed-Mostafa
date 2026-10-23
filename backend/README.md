# Smart CGM family sync (Cloudflare Worker + D1, free plan)

Replaces Firebase. Live at `https://smartcgm-sync.mflma2040.workers.dev` (the app's default backend URL).

- Patient phone: `POST /v1/p/{code}/register {token}` once, then `POST /readings`, `PUT /status` with `Authorization: Bearer <token>`.
- Invites: patient `POST /v1/p/{code}/invites {role: ADMIN|VIEWER}` → one-time code valid 24 h; follower `POST /v1/invites/redeem {code}` → own token.
- ADMIN follower: `POST /events` (dose ≤ 50 U, carbs ≤ 300 g), `PUT /proposal {low, high}` (low 60–100 and > 55, high 120–300, gap ≥ 30; any other key such as urgentLow is rejected). Nothing changes until the patient approves on their phone.
- Patient: `GET/DELETE /events`, `GET/DELETE /proposal`, `GET/DELETE /followers` (revoke).
- Tokens are stored only as SHA-256 hashes. No secrets in this repo. Readings older than 30 days are pruned.
- No push service (no FCM/Firebase): followers poll every minute.

Test: `npm test` (Node 22, uses `node:sqlite` as a D1 stand-in).
Deploy: `npx wrangler login && npm run deploy` (applies `migrations/` then deploys).
