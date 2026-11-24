-- A revert link for an email change (adversarial review of 23 Nov 2026, AUTH-13). When a sign-in
-- email changes, the old address is sent a link, valid 7 days and single use, that puts the old
-- address back, sets a new password and signs out every session: someone who took over a session
-- and moved the account to their own mailbox cannot keep it. The link is a row of its own, kind
-- REVERT: old_email is the address it replaces (the changed one), new_email the address it puts
-- back. A later request or change never cancels it; only another revert does (see the code).
--
-- access.email_changes is platform scope (accounts belong to no pharmacy): no row-level security.
alter table access.email_changes drop constraint email_changes_kind_check;
alter table access.email_changes
  add constraint email_changes_kind_check check (kind in ('CHANGE', 'RECOVERY', 'REVERT'));
