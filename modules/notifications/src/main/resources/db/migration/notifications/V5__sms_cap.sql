-- The plan's SMS a month on the platform's account (adversarial review INT-5).
--
-- reason: why the platform refused a message rather than a provider failing, as a stable code
-- (sms_cap_reached). Existing rows keep null: no row is changed.
alter table notifications.messages add column reason text;

-- Counting a pharmacy's SMS sent this month, before each SMS on the platform's account.
create index messages_sent_sms on notifications.messages (tenant_uid, sent_at)
  where channel = 'SMS' and status = 'SENT';
