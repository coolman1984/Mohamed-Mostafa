// Minimal in-memory D1 stand-in built on node:sqlite, enough for the worker's queries.
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';

export function makeDb() {
  const s = new DatabaseSync(':memory:');
  s.exec(readFileSync(new URL('../migrations/0001_init.sql', import.meta.url), 'utf8'));
  const stmt = (sql, args = []) => ({
    bind: (...a) => stmt(sql, a),
    first: async () => s.prepare(sql).get(...args) ?? null,
    all: async () => ({ results: s.prepare(sql).all(...args) }),
    run: async () => s.prepare(sql).run(...args),
  });
  return {
    prepare: (sql) => stmt(sql),
    batch: async (list) => { s.exec('BEGIN'); try { for (const x of list) await x.run(); s.exec('COMMIT'); } catch (e) { s.exec('ROLLBACK'); throw e; } },
  };
}
