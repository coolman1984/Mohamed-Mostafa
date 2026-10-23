import { test } from 'node:test';
import assert from 'node:assert/strict';
import { handle, proposalProblem } from '../src/worker.js';
import { makeDb } from './d1shim.js';

const PID = 'ABCDEFGHJKMNPQRS';
const OWNER = 'o'.repeat(40);
function setup() {
  const env = { DB: makeDb() };
  const call = async (method, path, { token, body, now } = {}) => {
    const headers = { 'content-type': 'application/json' };
    if (token) headers.authorization = 'Bearer ' + token;
    const r = await handle(new Request('https://x.test' + path, { method, headers, body: body ? JSON.stringify(body) : undefined }), env, now);
    return { status: r.status, body: await r.json() };
  };
  return { env, call };
}
async function withFollower(call, role) {
  await call('POST', `/v1/p/${PID}/register`, { body: { token: OWNER } });
  const inv = await call('POST', `/v1/p/${PID}/invites`, { token: OWNER, body: { role } });
  const red = await call('POST', '/v1/invites/redeem', { body: { code: 'SMARTCGM:' + inv.body.code } });
  return red.body.token;
}

test('health', async () => { const { call } = setup(); assert.equal((await call('GET', '/')).body.ok, true); });

test('register is first-come and idempotent for the same token', async () => {
  const { call } = setup();
  assert.equal((await call('POST', `/v1/p/${PID}/register`, { body: { token: OWNER } })).status, 201);
  assert.equal((await call('POST', `/v1/p/${PID}/register`, { body: { token: OWNER } })).status, 200);
  assert.equal((await call('POST', `/v1/p/${PID}/register`, { body: { token: 'x'.repeat(40) } })).status, 409);
  assert.equal((await call('POST', `/v1/p/${PID}/register`, { body: { token: 'short' } })).status, 400);
});

test('owner uploads, follower reads; follower cannot upload; no token = 401', async () => {
  const { call } = setup();
  const v = await withFollower(call, 'VIEWER');
  const now = Date.now();
  const up = await call('POST', `/v1/p/${PID}/readings`, { token: OWNER, body: { readings: [[now - 60000, 101.4], [now, 99], [now, 9999]] } });
  assert.deepEqual(up.body, { stored: 2, rejected: 1 });
  const got = await call('GET', `/v1/p/${PID}/readings?after=0`, { token: v });
  assert.deepEqual(got.body.readings, [[now - 60000, 101], [now, 99]]);
  assert.equal((await call('GET', `/v1/p/${PID}/readings?after=${now - 60000}`, { token: v })).body.readings.length, 1);
  assert.equal((await call('POST', `/v1/p/${PID}/readings`, { token: v, body: { readings: [] } })).status, 403);
  assert.equal((await call('GET', `/v1/p/${PID}/readings`)).status, 401);
  assert.equal((await call('GET', `/v1/p/ZZZZZZZZZZZZZZZZ/readings`, { token: v })).status, 401);
});

test('invite is one-time and expires', async () => {
  const { call } = setup();
  await call('POST', `/v1/p/${PID}/register`, { body: { token: OWNER } });
  const inv = await call('POST', `/v1/p/${PID}/invites`, { token: OWNER, body: { role: 'ADMIN' } });
  assert.equal((await call('POST', '/v1/invites/redeem', { body: { code: inv.body.code } })).body.role, 'ADMIN');
  assert.equal((await call('POST', '/v1/invites/redeem', { body: { code: inv.body.code } })).status, 404);
  const inv2 = await call('POST', `/v1/p/${PID}/invites`, { token: OWNER, body: { role: 'VIEWER' } });
  assert.equal((await call('POST', '/v1/invites/redeem', { body: { code: inv2.body.code }, now: Date.now() + 25 * 3600_000 })).status, 404);
});

test('status round trip keeps only known numeric fields', async () => {
  const { call } = setup();
  const v = await withFollower(call, 'VIEWER');
  await call('PUT', `/v1/p/${PID}/status`, { token: OWNER, body: { mgdl: 120, sensorStart: 5, evil: 'x' } });
  const st = (await call('GET', `/v1/p/${PID}/status`, { token: v })).body.status;
  assert.equal(st.mgdl, 120); assert.equal(st.sensorStart, 5); assert.equal(st.evil, undefined);
});

test('events: only ADMIN sends, only owner reads/deletes, values bounded', async () => {
  const { call } = setup();
  const a = await withFollower(call, 'ADMIN');
  const inv = await call('POST', `/v1/p/${PID}/invites`, { token: OWNER, body: { role: 'VIEWER' } });
  const v = (await call('POST', '/v1/invites/redeem', { body: { code: inv.body.code } })).body.token;
  assert.equal((await call('POST', `/v1/p/${PID}/events`, { token: v, body: { type: 'dose', value: 2 } })).status, 403);
  assert.equal((await call('POST', `/v1/p/${PID}/events`, { token: a, body: { type: 'dose', value: 80 } })).status, 400);
  assert.equal((await call('POST', `/v1/p/${PID}/events`, { token: a, body: { type: 'carbs', value: 30 } })).status, 201);
  assert.equal((await call('GET', `/v1/p/${PID}/events`, { token: a })).status, 403);
  const ev = (await call('GET', `/v1/p/${PID}/events`, { token: OWNER })).body.events;
  assert.equal(ev.length, 1);
  await call('DELETE', `/v1/p/${PID}/events/${ev[0].id}`, { token: OWNER });
  assert.equal((await call('GET', `/v1/p/${PID}/events`, { token: OWNER })).body.events.length, 0);
});

test('proposal: ranges enforced, urgent low not proposable, owner-only read', async () => {
  const { call } = setup();
  const a = await withFollower(call, 'ADMIN');
  assert.equal((await call('PUT', `/v1/p/${PID}/proposal`, { token: a, body: { low: 50, high: 180 } })).status, 422);
  assert.equal((await call('PUT', `/v1/p/${PID}/proposal`, { token: a, body: { low: 70, high: 90 } })).status, 422);
  assert.equal((await call('PUT', `/v1/p/${PID}/proposal`, { token: a, body: { low: 70, high: 180, urgentLow: 40 } })).status, 422);
  const ok = await call('PUT', `/v1/p/${PID}/proposal`, { token: a, body: { low: 70, high: 180 } });
  assert.equal(ok.body.needsPatientApproval, true);
  assert.equal((await call('GET', `/v1/p/${PID}/proposal`, { token: a })).status, 403);
  assert.equal((await call('GET', `/v1/p/${PID}/proposal`, { token: OWNER })).body.proposal.low, 70);
  await call('DELETE', `/v1/p/${PID}/proposal`, { token: OWNER });
  assert.equal((await call('GET', `/v1/p/${PID}/proposal`, { token: OWNER })).body.proposal, null);
});

test('proposalProblem unit', () => {
  assert.equal(proposalProblem({ low: 70, high: 180 }), null);
  assert.ok(proposalProblem({ low: 55, high: 180 }));
  assert.ok(proposalProblem({ low: 70, high: 400 }));
  assert.ok(proposalProblem({ low: '70', high: 180 }));
});

test('owner can list and revoke followers', async () => {
  const { call } = setup();
  const v = await withFollower(call, 'VIEWER');
  const list = (await call('GET', `/v1/p/${PID}/followers`, { token: OWNER })).body.followers;
  assert.equal(list.length, 1);
  await call('DELETE', `/v1/p/${PID}/followers/${list[0].id}`, { token: OWNER });
  assert.equal((await call('GET', `/v1/p/${PID}/status`, { token: v })).status, 401);
});
