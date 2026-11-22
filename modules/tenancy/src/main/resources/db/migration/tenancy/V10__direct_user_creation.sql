-- Whether a pharmacy's admin may create staff logins directly (managed logins, username@CODE),
-- rather than only invite people by email. Only the root administrator changes it. On for every
-- pharmacy: small pharmacies' cashiers often have no email, and existing pharmacies keep working
-- as they do today.
alter table tenancy.tenants add column direct_user_creation boolean not null default true;
