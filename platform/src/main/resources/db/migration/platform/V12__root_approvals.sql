-- FRD-18 (adversarial review of 23 Nov 2026; owner's decision of 24 Nov 2026): lifting a
-- pharmacy's legal hold and erasing a pharmacy need two root administrators. One asks (with a
-- reason, and for an erasure the pharmacy's code typed again); a different one approves with their
-- password, within 24 hours, and the action then runs once. With only one active root
-- administrator, both actions are refused.
--
-- platform.root_approvals is platform data (no pharmacy owns a row, the pharmacy it is about is
-- named in subject_tenant_uid), so it has no row-level security; only root administrators reach it
-- through the API. A request is never deleted: it ends EXECUTED, CANCELLED or EXPIRED (marked so
-- when it is next read or a new one is asked for, once past expires_at).
-- At most one open request per pharmacy and action (a partial unique index), so two instances
-- cannot open two. Approving takes the row FOR UPDATE and moves it out of PENDING in the same
-- transaction as the action: it runs once, whichever instance approves.
-- A new table only: no existing row is changed.
create table platform.root_approvals (
  id                 bigint generated always as identity primary key,
  uid                uuid not null unique,
  action             text not null check (action in ('LIFT_LEGAL_HOLD', 'ERASE_TENANT')),
  subject_tenant_uid uuid not null,
  reason             text not null check (length(reason) between 1 and 500),
  status             text not null default 'PENDING'
                       check (status in ('PENDING', 'EXECUTED', 'CANCELLED', 'EXPIRED')),
  requested_by_uid   uuid not null,
  requested_by_name  text not null,
  requested_at       timestamptz not null,
  expires_at         timestamptz not null,
  approved_by_uid    uuid,
  approved_by_name   text,
  approved_at        timestamptz,
  cancelled_by_uid   uuid,
  cancelled_at       timestamptz,
  constraint root_approvals_two_people
    check (approved_by_uid is null or approved_by_uid <> requested_by_uid),
  constraint root_approvals_executed_is_approved
    check (status <> 'EXECUTED' or (approved_by_uid is not null and approved_at is not null)),
  check (expires_at > requested_at)
);
create unique index root_approvals_one_open
  on platform.root_approvals (subject_tenant_uid, action) where status = 'PENDING';
create index root_approvals_by_time on platform.root_approvals (requested_at desc);
grant select, insert, update on platform.root_approvals to ${application_role};
