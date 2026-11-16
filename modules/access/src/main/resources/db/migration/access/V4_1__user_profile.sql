-- A person's phone (E.164), for SMS and WhatsApp notifications.

alter table access.users
  add column phone text check (phone is null or phone ~ '^\+[1-9][0-9]{7,14}$');
