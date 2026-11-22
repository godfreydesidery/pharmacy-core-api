-- Closing a pharmacy and erasing its data (the owner's request, or the end of a lapsed account),
-- under Tanzania's Personal Data Protection Act 2022: the pharmacy takes its data out (export),
-- closes, and after a cooling-off period its data is erased. A legal hold (a dispute, a regulator's
-- or court's order) stops the erasure until it is lifted.
--   CLOSED  no one works in it; its admins may still read and export; it can be reopened until
--           erase_after; after that a root admin may erase it.
--   ERASED  its rows and files are gone; this row stays as a tombstone (who, when) without its
--           contact details. The platform's own billing records (subscriptions, invoices) stay: they
--           are the platform's tax records.
alter table tenancy.tenants drop constraint tenants_status_check;
alter table tenancy.tenants add constraint tenants_status_check
  check (status in ('ACTIVE', 'SUSPENDED', 'CLOSED', 'ERASED'));

alter table tenancy.tenants
  add column legal_hold        boolean not null default false,
  add column legal_hold_reason text,
  add column closed_at         timestamptz,
  add column erase_after       timestamptz,
  add column erased_at         timestamptz;

update tenancy.tenants set legal_hold = true, legal_hold_reason = status_reason
 where suspension_kind = 'DISPUTE';
