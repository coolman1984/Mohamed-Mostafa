# Smart CGM Cloudflare backend — API contract (v1, api: 2)

Base URL: `https://smartcgm-sync.mflma2040.workers.dev` · Worker + one D1 database, Cloudflare free plan.
Code: `backend/src/worker.js` · Tests: `cd backend && npm test` · Schema: `backend/migrations/`.

## Auth
- Every phone holds its own random bearer token (32–128 chars). The server stores only SHA-256(token).
- `Authorization: Bearer <token>` on every call except `register` and `invites/redeem`.
- Roles: `OWNER` (patient phone: uploads), `ADMIN` (follower: events, threshold proposals), `VIEWER` (read only).
- Patient id: 12–32 chars from `23456789ABCDEFGHJKMNPQRSTUVWXYZ`.
- No secrets live in the repo or the Worker. Never log tokens.

## Reading object
Legacy tuple `[t, mgdl]` is still accepted everywhere. New sources should send objects:

| field | type | required | rules |
|---|---|---|---|
| `t` | integer ms epoch | yes | > 1e12, < now + 1 h, not older than 30 days |
| `mgdl` | number | yes | 20–600, rounded |
| `source` | string | no | `juggluco` · `notification` · `linx_ble` · `manual` |
| `serial` | string | no | `[A-Za-z0-9_-]{1,32}` (sensor serial) |
| `ageDays` | number | no | 0–30 (sensor age in days, fractional ok) |
| `trend` | string | no | `rising_fast` · `rising` · `steady` · `falling` · `falling_fast` |

Invalid items are counted in `rejected`; the rest of the request still succeeds. Primary key is `(patient, t)`.

## Endpoints
| method & path | role | notes |
|---|---|---|
| `GET /` or `/v1` | – | `{ok, service, api: 2, sources}` health check |
| `POST /v1/p/{pid}/register` `{token}` | – | first phone becomes OWNER; same token again = 200 |
| `POST /v1/p/{pid}/readings` `{readings:[…≤500]}` | OWNER | live upload; same `t` → newest value wins. `{stored, rejected}` |
| `POST /v1/p/{pid}/readings/batch` `{readings:[…≤1440]}` | OWNER | **history backfill, idempotent**: existing `(patient,t)` rows are kept. Body ≤ 240 KB. `{received, inserted, duplicates, rejected}` |
| `GET /v1/p/{pid}/readings?after=ms` | any | legacy: last 24 h as `[[t,mgdl],…]`, ≤1440 rows, oldest first |
| `GET /v1/p/{pid}/readings?after=ms&format=full&days=1..30` | any | objects with source/serial/ageDays/trend when known; page with `after` = last `t` |
| `PUT /v1/p/{pid}/status` | OWNER | numbers `time, mgdl, iob, cob, sensorStart` + `source, serial, ageDays, trend` (same rules) |
| `GET /v1/p/{pid}/status` | any | `{status}` incl. server `updated` |
| `POST /v1/p/{pid}/invites` `{role}` | OWNER | one-time code, 24 h |
| `POST /v1/invites/redeem` `{code, name}` | – | `{patient, role, token}` |
| `GET/DELETE /v1/p/{pid}/followers[/{id}]` | OWNER | list / revoke |
| `POST/GET/DELETE /v1/p/{pid}/events[/{id}]` | ADMIN posts, OWNER reads | dose ≤ 50 U, carbs ≤ 300 g |
| `PUT/GET/DELETE /v1/p/{pid}/proposal` | ADMIN proposes, OWNER reads | low/high only; urgent low 55 is never remote |

Errors: `{error}` with 400/401/403/404/409/413/422/429/500.

## How a `LinxBleSource` should plug in
1. Live: every new minute reading → `POST /readings` with `{t, mgdl, source:"linx_ble", serial, ageDays, trend}` (queue offline, send in chunks ≤ 500), then `PUT /status`.
2. After (re)connect, read the sensor's stored history and send it with `POST /readings/batch` in chunks of ≤ 1440 rows. Retrying the same chunk is safe (dedupe by patient + timestamp).
3. Timestamps must be absolute ms UTC computed on the phone from sensor offset + sensor start.
4. Free-plan budget: each upload is 2 D1 queries no matter how many rows (single `json_each` insert), so uploading every 1–5 min is far below the 100k requests/day limit.
5. Followers keep polling `GET /readings?after=` every minute; there is no push.
