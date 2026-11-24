-- WEB-2: an idempotency key is the sale's, not any sale's. The fingerprint of the request that made
-- the sale (SHA-256 hex of its canonical form, the key itself left out), so the same key sent with
-- a different cart is refused (422 idempotency_key_reused) instead of answering with the first
-- sale. Null on the sales made before it: those keep answering with their sale.
alter table sales.sales
  add column request_fingerprint text;
