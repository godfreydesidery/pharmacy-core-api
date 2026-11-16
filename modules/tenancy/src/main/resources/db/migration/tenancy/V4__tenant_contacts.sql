-- Who to contact at a pharmacy, kept on the registry row so the root administrator can edit it.

alter table tenancy.tenants
  add column contact_name  text,
  add column contact_email text,
  add column contact_phone text,
  add column address       text;
