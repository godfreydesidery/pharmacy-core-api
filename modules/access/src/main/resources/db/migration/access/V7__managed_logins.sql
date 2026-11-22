-- Managed logins: staff logins a pharmacy's admin creates directly, for people who have no email
-- or must start at the till the same day. Such a login belongs to that one pharmacy
-- (managed_by_tenant_uid) and signs in as username@PHARMACYCODE, which the email column holds in
-- lower case (juma@dawa). A real email always has a dot after the @, so the two never collide.
-- contact_email is where mail to the person goes; it is not unique and not used to sign in.
-- password_change_required: the person must choose their own password before working (after the
-- admin set or reset a temporary one).
alter table access.users
  add column managed_by_tenant_uid uuid,
  add column contact_email text check (contact_email is null or contact_email = lower(contact_email)),
  add column password_change_required boolean not null default false;
create index users_managed_by_tenant on access.users (managed_by_tenant_uid)
  where managed_by_tenant_uid is not null;
