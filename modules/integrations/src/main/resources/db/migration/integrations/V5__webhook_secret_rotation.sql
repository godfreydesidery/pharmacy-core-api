-- Webhook signing secrets (adversarial review INT-13).
--
-- webhooks.secret now holds the secret sealed with the platform key (AES-256-GCM under
-- pharmacy.access.encryption-key, as message provider secrets are). A webhook saved before this
-- release still holds its secret in plain text (whsec_<hex>); the application seals it the first
-- time the webhook is used or the minute retry job passes by. SQL cannot seal (the key is not in
-- the database), so this migration changes no rows.
--
-- After a rotation the previous secret, sealed, keeps signing until previous_until (24 hours), so
-- the receiving system can switch over without missing a delivery.
alter table integrations.webhooks
  add column previous_secret text,
  add column previous_until  timestamptz;
