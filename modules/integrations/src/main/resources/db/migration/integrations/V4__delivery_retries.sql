-- Webhook deliveries are retried over about two days (at-least-once, as Stripe and GitHub do):
-- after each failed round the next attempt is due 1 min, 5 min, 30 min, 2 h, 6 h, 12 h, then 24 h
-- later; after that the delivery stays FAILED for good. Older failed deliveries keep no next
-- attempt (they are not retried by surprise).
alter table integrations.deliveries
  add column rounds          int not null default 0,
  add column next_attempt_at timestamptz;

create index deliveries_due on integrations.deliveries (next_attempt_at)
  where status = 'FAILED' and next_attempt_at is not null;
