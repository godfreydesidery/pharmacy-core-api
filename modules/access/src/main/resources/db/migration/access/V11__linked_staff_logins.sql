-- A pharmacy's staff login (managed login) linked by its person to their personal account (gap
-- G22): the login is switched off and remembers the account it went to; its membership now belongs
-- to the personal account. Records it made keep its uid, as history.
alter table access.users add column linked_to_uid uuid references access.users (uid);
alter table access.users add column linked_at timestamptz;
