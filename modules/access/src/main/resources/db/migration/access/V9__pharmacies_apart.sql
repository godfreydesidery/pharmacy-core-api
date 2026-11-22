-- What one pharmacy may know of a person who also works elsewhere: nothing of the others.

-- The pharmacies a session has entered. A sign-in is recorded in a pharmacy's audit when the
-- session first works there, and a sign-out only in the pharmacies it entered, so no pharmacy
-- learns when its staff sign in to work somewhere else. Platform scope, like sessions.
create table access.session_pharmacies (
  session_uid uuid        not null references access.sessions (uid),
  tenant_uid  uuid        not null,
  entered_at  timestamptz not null,
  primary key (session_uid, tenant_uid)
);
grant select, insert on access.session_pharmacies to ${application_role};

-- When the person last worked in this pharmacy (not when they last signed in anywhere), and, once
-- they leave, their name, email and phone as they were then: a former employer does not see what
-- the person changes afterwards.
alter table access.memberships
  add column last_active_at timestamptz,
  add column former_name    text,
  add column former_email   text,
  add column former_phone   text;

-- Fill both from what is known. Migrations run as the owner, whom forced row-level security would
-- hold to no tenant: it is lifted for the update and restored.
alter table access.memberships no force row level security;
alter table audit.entries no force row level security;

update access.memberships m
   set last_active_at = s.at
  from (select tenant_uid, entity_uid, max(at) as at
          from audit.entries
         where action = 'SIGN_IN' and tenant_uid is not null and entity_uid is not null
         group by tenant_uid, entity_uid) s
 where s.tenant_uid = m.tenant_uid and s.entity_uid = m.user_uid;

update access.memberships m
   set former_name = u.name, former_email = u.email, former_phone = u.phone
  from access.users u
 where u.uid = m.user_uid and not m.active;

alter table audit.entries force row level security;
alter table access.memberships force row level security;
