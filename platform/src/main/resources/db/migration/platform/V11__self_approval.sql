-- The owner's decision of 24 Nov 2026, "owner may self-approve if alone": a pharmacy's owner who
-- has nobody else able to approve may approve their own request, after typing their password. Such
-- an approval is marked, so the owner's digest and the audit trail show it for what it is. Every
-- other approval is still another person's: the check now allows the requester only when marked.
-- Adding a column with a constant default rewrites nothing; existing rows are not self-approvals.

alter table platform.approvals add column self_approved boolean not null default false;

alter table platform.approvals drop constraint if exists approvals_check;
alter table platform.approvals
  add constraint approvals_approver_is_another
  check (self_approved or approved_by_uid <> requested_by_uid);
