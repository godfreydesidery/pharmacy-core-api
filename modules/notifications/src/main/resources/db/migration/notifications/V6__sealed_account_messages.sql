-- Account messages keep no live link or code at rest (adversarial review of 23 Nov 2026, AUTH-8).
-- Batch 2 forgot the text of an account message once sent; one still queued, or failed, kept its
-- reset, invitation or recovery link (or its code) in plain text until it was sent or purged. From
-- now on such a message's text is sealed with the keyring (purpose "messages") from the moment it is
-- queued, opened only to send it, and forgotten once sent or given up on. sealed says which rows
-- hold sealed text.
alter table notifications.messages add column sealed boolean not null default false;

-- Account messages already given up on (no attempt left), or queued for more than a day (their
-- links have expired), will never be sent: their text goes now. The ones still due are sent within
-- the hour and forgotten then. Every pharmacy's rows and the platform's: the force is lifted around
-- the update, which otherwise reaches none of them (forced row-level security binds the owner too).
alter table notifications.messages no force row level security;
update notifications.messages
   set body = '(Not sent. Account messages are not kept: they carry links and codes.)',
       html_body = null
 where category = 'access'
   and status <> 'SENT'
   and (next_attempt_at is null or created_at < now() - interval '1 day');
alter table notifications.messages force row level security;
