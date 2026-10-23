// Smart CGM family sync on Cloudflare Workers + D1 (free tier). Replaces the old Firebase Realtime Database.
//
// Auth: every phone holds its own random bearer token. The server keeps only SHA-256(token).
//   OWNER  = the patient's phone (registers once, uploads readings, approves proposals)
//   ADMIN  = primary follower (reads, logs doses/carbs, PROPOSES thresholds)
//   VIEWER = read-only follower
// Safety (AGENTS.md): urgent-low (55) is never remotely changeable; proposals are range-checked here AND on the
// patient's phone, and nothing changes until the patient approves on their own phone.

export const LIMITS = {
  MIN_LOW: 60, MAX_LOW: 100, MIN_HIGH: 120, MAX_HIGH: 300, MIN_GAP: 30, URGENT_LOW: 55,
  MAX_BATCH: 500, MAX_BACKFILL: 1440, MAX_BODY: 64_000, MAX_BACKFILL_BODY: 240_000, MIN_MGDL: 20, MAX_MGDL: 600, KEEP_DAYS: 30, INVITE_TTL_MS: 24 * 3600_000,
  MAX_PULL: 1440, MAX_EVENTS: 50, MAX_DOSE_U: 50, MAX_CARBS_G: 300, MAX_FOLLOWERS: 20,
};
const ALPHABET = '23456789ABCDEFGHJKMNPQRSTUVWXYZ';
export const SOURCES = ['juggluco', 'notification', 'linx_ble', 'manual'];
export const TRENDS = ['rising_fast', 'rising', 'steady', 'falling', 'falling_fast'];
const SERIAL_RE = /^[A-Za-z0-9_-]{1,32}$/;

// Accepts the legacy [t, mgdl] tuple or {t, mgdl, source?, serial?, ageDays?, trend?}. Returns a clean row or null.
export function normalizeReading(r, now) {
  let o;
  if (Array.isArray(r)) o = { t: r[0], mgdl: r[1] };
  else if (r && typeof r === 'object') o = r;
  else return null;
  const { t, mgdl } = o;
  if (!Number.isInteger(t) || t <= 1e12 || t >= now + 3600_000) return null;
  if (typeof mgdl !== 'number' || !(mgdl >= LIMITS.MIN_MGDL && mgdl <= LIMITS.MAX_MGDL)) return null;
  if (o.source != null && !SOURCES.includes(o.source)) return null;
  if (o.serial != null && !(typeof o.serial === 'string' && SERIAL_RE.test(o.serial))) return null;
  if (o.ageDays != null && !(typeof o.ageDays === 'number' && o.ageDays >= 0 && o.ageDays <= 30)) return null;
  if (o.trend != null && !TRENDS.includes(o.trend)) return null;
  return [t, Math.round(mgdl), o.source ?? null, o.serial ?? null, o.ageDays ?? null, o.trend ?? null];
}

// One SQL statement per request (json_each) so even a 1440-row backfill is a single D1 query on the free plan.
function insertRows(db, pid, rows, conflict) {
  return db.prepare(`INSERT INTO readings (patient, t, mgdl, source, serial, age_days, trend)
    SELECT ?1, json_extract(value,'$[0]'), json_extract(value,'$[1]'), json_extract(value,'$[2]'),
      json_extract(value,'$[3]'), json_extract(value,'$[4]'), json_extract(value,'$[5]') FROM json_each(?2) WHERE true
    ON CONFLICT(patient, t) DO ${conflict === 'replace'
      ? 'UPDATE SET mgdl=excluded.mgdl, source=excluded.source, serial=excluded.serial, age_days=excluded.age_days, trend=excluded.trend'
      : 'NOTHING'}`).bind(pid, JSON.stringify(rows));
}
const changes = (r) => (r?.meta?.changes ?? r?.changes ?? 0);

const ID_RE = /^[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{12,32}$/;

export function proposalProblem(p) {
  if (!p || typeof p !== 'object') return 'bad body';
  const extra = Object.keys(p).filter((k) => !['low', 'high', 'time'].includes(k));
  if (extra.length) return 'only low/high may be proposed (urgent low is fixed)';
  const { low, high } = p;
  if (typeof low !== 'number' || typeof high !== 'number' || !isFinite(low) || !isFinite(high)) return 'numbers required';
  if (low < LIMITS.MIN_LOW || low > LIMITS.MAX_LOW) return 'low out of range';
  if (low <= LIMITS.URGENT_LOW) return 'low must be above urgent low';
  if (high < LIMITS.MIN_HIGH || high > LIMITS.MAX_HIGH) return 'high out of range';
  if (high - low < LIMITS.MIN_GAP) return 'gap too small';
  return null;
}

async function sha256(s) {
  const d = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(s));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, '0')).join('');
}
function randomCode(n) {
  const b = crypto.getRandomValues(new Uint8Array(n));
  return [...b].map((x) => ALPHABET[x % ALPHABET.length]).join('');
}
function randomToken() {
  const b = crypto.getRandomValues(new Uint8Array(32));
  return [...b].map((x) => x.toString(16).padStart(2, '0')).join('');
}
const json = (body, status = 200) => new Response(JSON.stringify(body), {
  status, headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' },
});
const err = (status, message) => json({ error: message }, status);

async function body(req, max = LIMITS.MAX_BODY) {
  const text = await req.text();
  if (text.length > max) throw new HttpError(413, 'too large');
  try { return text ? JSON.parse(text) : {}; } catch { throw new HttpError(400, 'bad json'); }
}
class HttpError extends Error { constructor(status, msg) { super(msg); this.status = status; } }

async function auth(req, db, patient, roles) {
  const h = req.headers.get('authorization') || '';
  const tok = h.startsWith('Bearer ') ? h.slice(7).trim() : '';
  if (tok.length < 32 || tok.length > 128) throw new HttpError(401, 'token required');
  const row = await db.prepare('SELECT patient, role FROM tokens WHERE hash = ?').bind(await sha256(tok)).first();
  if (!row || row.patient !== patient) throw new HttpError(401, 'bad token');
  if (!roles.includes(row.role)) throw new HttpError(403, 'not allowed for ' + row.role);
  return row.role;
}

export async function handle(req, env, now = Date.now()) {
  const db = env.DB;
  const url = new URL(req.url);
  const parts = url.pathname.split('/').filter(Boolean);
  const m = req.method;
  try {
    if (parts.length === 0 || (parts[0] === 'v1' && parts.length === 1)) return json({ ok: true, service: 'smartcgm-sync', api: 2, sources: SOURCES });
    if (parts[0] !== 'v1') return err(404, 'not found');

    // Follower redeems a one-time invite code -> gets its own token.
    if (m === 'POST' && parts[1] === 'invites' && parts[2] === 'redeem' && parts.length === 3) {
      const b = await body(req);
      const code = String(b.code || '').trim().toUpperCase().replace(/^SMARTCGM:/, '');
      const inv = await db.prepare('SELECT patient, role, expires FROM invites WHERE code = ?').bind(code).first();
      if (!inv || inv.expires < now) return err(404, 'invite not found or expired');
      const n = await db.prepare("SELECT COUNT(*) AS n FROM tokens WHERE patient = ? AND role != 'OWNER'").bind(inv.patient).first();
      if (n.n >= LIMITS.MAX_FOLLOWERS) return err(409, 'too many followers');
      const token = randomToken();
      const name = String(b.name || '').slice(0, 40);
      await db.batch([
        db.prepare('DELETE FROM invites WHERE code = ?').bind(code),
        db.prepare('INSERT INTO tokens (hash, patient, role, name, created) VALUES (?,?,?,?,?)')
          .bind(await sha256(token), inv.patient, inv.role, name, now),
      ]);
      return json({ patient: inv.patient, role: inv.role, token });
    }

    if (parts[1] !== 'p' || !ID_RE.test(parts[2] || '')) return err(404, 'not found');
    const pid = parts[2];
    const res = parts[3];
    const sub = parts[4];

    if (res === 'register' && m === 'POST' && parts.length === 4) {
      // First phone to register a (random, 16-char) id becomes its owner. Re-registering needs the same token.
      const b = await body(req);
      const tok = String(b.token || '');
      if (tok.length < 32 || tok.length > 128) return err(400, 'token too short');
      const h = await sha256(tok);
      const owner = await db.prepare("SELECT hash FROM tokens WHERE patient = ? AND role = 'OWNER'").bind(pid).first();
      if (owner) return owner.hash === h ? json({ ok: true }) : err(409, 'id already registered');
      await db.batch([
        db.prepare('INSERT OR IGNORE INTO patients (id, created) VALUES (?,?)').bind(pid, now),
        db.prepare("INSERT INTO tokens (hash, patient, role, created) VALUES (?,?, 'OWNER', ?)").bind(h, pid, now),
      ]);
      return json({ ok: true }, 201);
    }

    if (res === 'invites' && m === 'POST') {
      await auth(req, db, pid, ['OWNER']);
      const b = await body(req);
      const role = b.role === 'ADMIN' ? 'ADMIN' : b.role === 'VIEWER' ? 'VIEWER' : null;
      if (!role) return err(400, 'role must be ADMIN or VIEWER');
      const code = randomCode(10);
      await db.batch([
        db.prepare('DELETE FROM invites WHERE expires < ?').bind(now),
        db.prepare('INSERT INTO invites (code, patient, role, expires) VALUES (?,?,?,?)').bind(code, pid, role, now + LIMITS.INVITE_TTL_MS),
      ]);
      return json({ code, role, expires: now + LIMITS.INVITE_TTL_MS }, 201);
    }

    if (res === 'followers') {
      await auth(req, db, pid, ['OWNER']);
      if (m === 'GET') {
        const r = await db.prepare("SELECT substr(hash,1,12) AS id, role, name, created FROM tokens WHERE patient = ? AND role != 'OWNER' ORDER BY created").bind(pid).all();
        return json({ followers: r.results });
      }
      if (m === 'DELETE' && sub && /^[0-9a-f]{12}$/.test(sub)) {
        await db.prepare("DELETE FROM tokens WHERE patient = ? AND role != 'OWNER' AND substr(hash,1,12) = ?").bind(pid, sub).run();
        return json({ ok: true });
      }
    }

    if (res === 'readings') {
      if (m === 'POST' && (sub === undefined || sub === 'batch') && parts.length <= 5) {
        // POST /readings      = live upload (<=500, newest value wins on same timestamp)
        // POST /readings/batch = history backfill (<=1440, idempotent: existing (patient,t) rows are kept)
        await auth(req, db, pid, ['OWNER']);
        const backfill = sub === 'batch';
        const max = backfill ? LIMITS.MAX_BACKFILL : LIMITS.MAX_BATCH;
        const b = await body(req, backfill ? LIMITS.MAX_BACKFILL_BODY : LIMITS.MAX_BODY);
        const list = Array.isArray(b.readings) ? b.readings : null;
        if (!list || list.length > max) return err(400, `readings: array of at most ${max}`);
        const keepFrom = now - LIMITS.KEEP_DAYS * 86400_000;
        const seen = new Map();
        let rejected = 0;
        for (const r of list) {
          const n = normalizeReading(r, now);
          if (!n || n[0] < keepFrom) { rejected++; continue; }
          seen.set(n[0], n); // duplicates inside one request: last one wins
        }
        const rows = [...seen.values()];
        const res1 = rows.length ? await insertRows(db, pid, rows, backfill ? 'ignore' : 'replace').run() : null;
        await db.prepare('DELETE FROM readings WHERE patient = ? AND t < ?').bind(pid, keepFrom).run();
        if (!backfill) return json({ stored: rows.length, rejected });
        const inserted = changes(res1);
        return json({ received: list.length, inserted, duplicates: rows.length - inserted + (list.length - rejected - rows.length), rejected });
      }
      if (m === 'GET' && parts.length === 4) {
        await auth(req, db, pid, ['OWNER', 'ADMIN', 'VIEWER']);
        const after = Number(url.searchParams.get('after') || 0);
        const full = url.searchParams.get('format') === 'full';
        const days = full ? Math.min(Math.max(Number(url.searchParams.get('days')) || 1, 1), LIMITS.KEEP_DAYS) : 1;
        const since = Math.max(Number.isFinite(after) ? after : 0, now - days * 86400_000);
        const r = await db.prepare('SELECT t, mgdl, source, serial, age_days, trend FROM readings WHERE patient = ? AND t > ? ORDER BY t LIMIT ?').bind(pid, since, LIMITS.MAX_PULL).all();
        if (!full) return json({ readings: r.results.map((x) => [x.t, x.mgdl]) });
        return json({ readings: r.results.map((x) => {
          const o = { t: x.t, mgdl: x.mgdl };
          if (x.source != null) o.source = x.source;
          if (x.serial != null) o.serial = x.serial;
          if (x.age_days != null) o.ageDays = x.age_days;
          if (x.trend != null) o.trend = x.trend;
          return o;
        }) });
      }
    }

    if (res === 'status') {
      if (m === 'PUT') {
        await auth(req, db, pid, ['OWNER']);
        const b = await body(req);
        const st = {};
        for (const k of ['time', 'mgdl', 'iob', 'cob', 'sensorStart']) if (typeof b[k] === 'number' && isFinite(b[k])) st[k] = b[k];
        if (SOURCES.includes(b.source)) st.source = b.source;
        if (typeof b.serial === 'string' && SERIAL_RE.test(b.serial)) st.serial = b.serial;
        if (typeof b.ageDays === 'number' && b.ageDays >= 0 && b.ageDays <= 30) st.ageDays = b.ageDays;
        if (TRENDS.includes(b.trend)) st.trend = b.trend;
        st.updated = now;
        await db.prepare('INSERT OR REPLACE INTO status (patient, json) VALUES (?,?)').bind(pid, JSON.stringify(st)).run();
        return json({ ok: true });
      }
      if (m === 'GET') {
        await auth(req, db, pid, ['OWNER', 'ADMIN', 'VIEWER']);
        const r = await db.prepare('SELECT json FROM status WHERE patient = ?').bind(pid).first();
        return json({ status: r ? JSON.parse(r.json) : null });
      }
    }

    if (res === 'events') {
      if (m === 'POST') {
        await auth(req, db, pid, ['ADMIN']);
        const b = await body(req);
        const max = b.type === 'dose' ? LIMITS.MAX_DOSE_U : b.type === 'carbs' ? LIMITS.MAX_CARBS_G : 0;
        if (!max || typeof b.value !== 'number' || !(b.value > 0) || b.value > max) return err(400, 'bad event');
        const n = await db.prepare('SELECT COUNT(*) AS n FROM events WHERE patient = ?').bind(pid).first();
        if (n.n >= LIMITS.MAX_EVENTS) return err(429, 'too many pending events');
        const t = Number.isInteger(b.time) && Math.abs(b.time - now) < 86400_000 ? b.time : now;
        await db.prepare('INSERT INTO events (patient, type, value, t) VALUES (?,?,?,?)').bind(pid, b.type, b.value, t).run();
        return json({ ok: true }, 201);
      }
      if (m === 'GET') {
        await auth(req, db, pid, ['OWNER']);
        const r = await db.prepare('SELECT id, type, value, t AS time FROM events WHERE patient = ? ORDER BY id').bind(pid).all();
        return json({ events: r.results });
      }
      if (m === 'DELETE' && /^\d+$/.test(sub || '')) {
        await auth(req, db, pid, ['OWNER']);
        await db.prepare('DELETE FROM events WHERE patient = ? AND id = ?').bind(pid, Number(sub)).run();
        return json({ ok: true });
      }
    }

    if (res === 'proposal') {
      if (m === 'PUT') {
        await auth(req, db, pid, ['ADMIN']);
        const b = await body(req);
        const p = proposalProblem(b);
        if (p) return err(422, p);
        await db.prepare('INSERT OR REPLACE INTO proposals (patient, low, high, t) VALUES (?,?,?,?)').bind(pid, b.low, b.high, now).run();
        return json({ ok: true, needsPatientApproval: true });
      }
      if (m === 'GET') {
        await auth(req, db, pid, ['OWNER']);
        const r = await db.prepare('SELECT low, high, t AS time FROM proposals WHERE patient = ?').bind(pid).first();
        return json({ proposal: r || null });
      }
      if (m === 'DELETE') {
        await auth(req, db, pid, ['OWNER']);
        await db.prepare('DELETE FROM proposals WHERE patient = ?').bind(pid).run();
        return json({ ok: true });
      }
    }
    return err(404, 'not found');
  } catch (e) {
    if (e instanceof HttpError) return err(e.status, e.message);
    return err(500, 'server error');
  }
}

export default { fetch: (req, env) => handle(req, env) };
