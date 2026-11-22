-- Why a pharmacy is suspended, as a kind the platform acts on (not only free text):
--   UNPAID   its subscription lapsed: its admins keep read-only access to pay and take their data;
--            paying lifts it.
--   DISPUTE  a legal hold (a dispute, a regulator's or court's order): nobody works in it and its
--            data may not be erased until the hold is lifted.
--   ABUSE    misuse of the platform: blocked.
--   OTHER    any other reason given in status_reason.
alter table tenancy.tenants add column suspension_kind text
  check (suspension_kind in ('UNPAID', 'DISPUTE', 'ABUSE', 'OTHER'));
update tenancy.tenants set suspension_kind = 'UNPAID'
 where status = 'SUSPENDED' and status_reason = 'Subscription unpaid';
update tenancy.tenants set suspension_kind = 'OTHER'
 where status = 'SUSPENDED' and suspension_kind is null;
