-- reporting.processed_events is purged by age (adversarial review PERF-13): an index on
-- processed_at, so the nightly purge finds the old markers without reading the whole table. Built
-- CONCURRENTLY, so projections keep writing while it builds; Flyway runs this script outside a
-- transaction (V3__processed_events_by_age.sql.conf). If it fails, see the runbook "Migrations".
create index concurrently if not exists processed_events_by_processed_at
  on reporting.processed_events (processed_at);
