-- WEB-9: a push payment request is bound to the sale it was sent for: the sale's idempotency key,
-- sent by the till as saleKey. A sale with another key cannot be paid with it (422
-- payment_mismatch). Null for the requests of older tills: those stay accepted.
alter table integrations.payment_intents
  add column sale_key text;
