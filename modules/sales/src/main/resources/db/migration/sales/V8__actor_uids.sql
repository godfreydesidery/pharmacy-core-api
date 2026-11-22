-- Who did it, by account: created_by_uid and updated_by_uid beside the email snapshot.
select platform.add_actor_uids('sales');

-- A shift is opened by its cashier, so its created_by_uid is the cashier's account: one open shift
-- per account and branch, whatever email the person signs in with.
drop index sales.shifts_one_open_per_cashier;
create unique index shifts_one_open_per_cashier
  on sales.shifts (tenant_uid, branch_uid, created_by_uid) where status = 'OPEN';
