-- Source-agnostic readings: where a value came from (juggluco / notification / linx_ble / manual) plus optional sensor info.
-- Old rows keep NULLs; old clients that send [t, mgdl] keep working.
ALTER TABLE readings ADD COLUMN source TEXT;
ALTER TABLE readings ADD COLUMN serial TEXT;
ALTER TABLE readings ADD COLUMN age_days REAL;
ALTER TABLE readings ADD COLUMN trend TEXT;
