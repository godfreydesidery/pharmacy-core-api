-- The account that accepted an invitation: the person may join with an account under another
-- email than the one invited, and the pharmacy then sees which.
alter table access.invitations add column accepted_by_user_uid uuid;
