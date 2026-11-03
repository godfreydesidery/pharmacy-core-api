-- access: users (platform-wide), sessions, and per tenant: roles, permissions and memberships.

grant usage on schema access to ${application_role};

-- Users sign in before any tenant is known, and one user may belong to several tenants, so this
-- table is platform-scope and carries no isolation policy; only the access module reads it.
create table access.users (
  id              bigint generated always as identity primary key,
  uid             uuid not null unique,
  email           text not null unique check (email = lower(email)),
  name            text not null,
  password_hash   text not null,
  root_admin      boolean not null default false,
  active          boolean not null default true,
  last_sign_in_at timestamptz,
  created_at      timestamptz not null,
  created_by      text not null,
  updated_at      timestamptz,
  updated_by      text,
  version         bigint not null default 0
);
create index users_root_admin on access.users (root_admin) where root_admin;
grant select, insert, update on access.users to ${application_role};

-- Only the SHA-256 hash of a session token is stored.
create table access.sessions (
  id         bigint generated always as identity primary key,
  uid        uuid not null unique,
  token_hash text not null unique,
  user_uid   uuid not null references access.users (uid),
  expires_at timestamptz not null,
  revoked_at timestamptz,
  created_at timestamptz not null,
  created_by text not null,
  updated_at timestamptz,
  updated_by text,
  version    bigint not null default 0
);
create index sessions_by_user_uid on access.sessions (user_uid);
grant select, insert, update on access.sessions to ${application_role};

create table access.roles (
  id          bigint generated always as identity primary key,
  uid         uuid not null unique,
  tenant_uid  uuid not null,
  code        text not null,
  name        text not null,
  description text,
  system      boolean not null default false,
  created_at  timestamptz not null,
  created_by  text not null,
  updated_at  timestamptz,
  updated_by  text,
  version     bigint not null default 0,
  unique (tenant_uid, code)
);
grant select, insert, update on access.roles to ${application_role};

create table access.role_permissions (
  role_id    bigint not null references access.roles (id) on delete cascade,
  permission text not null,
  primary key (role_id, permission)
);
grant select, insert, delete on access.role_permissions to ${application_role};

create table access.memberships (
  id           bigint generated always as identity primary key,
  uid          uuid not null unique,
  tenant_uid   uuid not null,
  user_uid     uuid not null references access.users (uid),
  role_id      bigint not null references access.roles (id),
  all_branches boolean not null,
  active       boolean not null default true,
  created_at   timestamptz not null,
  created_by   text not null,
  updated_at   timestamptz,
  updated_by   text,
  version      bigint not null default 0,
  unique (tenant_uid, user_uid)
);
create index memberships_by_user_uid on access.memberships (user_uid);
grant select, insert, update on access.memberships to ${application_role};

create table access.membership_branches (
  membership_id bigint not null references access.memberships (id) on delete cascade,
  branch_uid    uuid not null,
  primary key (membership_id, branch_uid)
);
grant select, insert, delete on access.membership_branches to ${application_role};

-- Memberships: a tenant sees its own; a signed-in user also sees their own in every tenant, to
-- sign in and choose a workspace. Writes are only ever the current tenant's.
alter table access.memberships enable row level security;
alter table access.memberships force row level security;
create policy tenant_or_own on access.memberships
  using (tenant_uid = platform.current_tenant_uid() or user_uid = platform.current_user_uid())
  with check (tenant_uid = platform.current_tenant_uid());

-- Roles: the tenant's own, and the roles of the current user's own memberships elsewhere (their
-- permissions decide what the user may do there). The policy reads memberships, whose policy does
-- not read roles, so the policies do not recurse.
alter table access.roles enable row level security;
alter table access.roles force row level security;
create policy tenant_or_own on access.roles
  using (
    tenant_uid = platform.current_tenant_uid()
    or exists (
      select 1 from access.memberships m
      where m.role_id = roles.id and m.user_uid = platform.current_user_uid()))
  with check (tenant_uid = platform.current_tenant_uid());

-- Child rows follow their parent's visibility.
alter table access.role_permissions enable row level security;
alter table access.role_permissions force row level security;
create policy follows_role on access.role_permissions
  using (exists (select 1 from access.roles r where r.id = role_id))
  with check (exists (select 1 from access.roles r where r.id = role_id));

alter table access.membership_branches enable row level security;
alter table access.membership_branches force row level security;
create policy follows_membership on access.membership_branches
  using (exists (select 1 from access.memberships m where m.id = membership_id))
  with check (exists (select 1 from access.memberships m where m.id = membership_id));
