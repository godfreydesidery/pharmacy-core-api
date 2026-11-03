-- access security: lockout, TOTP two-factor and recovery codes, sign-in challenges, passkeys
-- (WebAuthn), password resets, invitations and session metadata.
--
-- Rows that belong to a user (not to a tenant) are platform-scope like users and sessions: they
-- carry no tenant_uid and only the access module reads them, always by user or by token hash.

alter table access.users
  add column two_factor_enabled   boolean not null default false,
  add column totp_secret          text,
  add column totp_last_step       bigint,
  add column two_factor_enabled_at timestamptz,
  add column failed_sign_ins      integer not null default 0,
  add column first_failed_at      timestamptz,
  add column locked_until         timestamptz,
  add column webauthn_user_handle text unique;

alter table access.sessions
  add column ip           text,
  add column user_agent   text,
  add column last_seen_at timestamptz,
  add column method       text not null default 'PASSWORD';

-- Single-use recovery codes, stored as SHA-256 hashes.
create table access.recovery_codes (
  id         bigint generated always as identity primary key,
  uid        uuid not null unique,
  user_uid   uuid not null references access.users (uid),
  code_hash  text not null unique,
  used_at    timestamptz,
  created_at timestamptz not null,
  created_by text not null,
  updated_at timestamptz,
  updated_by text,
  version    bigint not null default 0
);
create index recovery_codes_by_user_uid on access.recovery_codes (user_uid);
grant select, insert, update, delete on access.recovery_codes to ${application_role};

-- The second step of a sign-in with two-factor on: the password was right, a code is due.
create table access.sign_in_challenges (
  id           bigint generated always as identity primary key,
  uid          uuid not null unique,
  token_hash   text not null unique,
  user_uid     uuid not null references access.users (uid),
  expires_at   timestamptz not null,
  attempts     integer not null default 0,
  consumed_at  timestamptz,
  ip           text,
  user_agent   text,
  created_at   timestamptz not null,
  created_by   text not null,
  updated_at   timestamptz,
  updated_by   text,
  version      bigint not null default 0
);
grant select, insert, update on access.sign_in_challenges to ${application_role};

-- WebAuthn credentials (passkeys). Keys and ids in base64url.
create table access.passkeys (
  id              bigint generated always as identity primary key,
  uid             uuid not null unique,
  user_uid        uuid not null references access.users (uid),
  credential_id   text not null unique,
  user_handle     text not null,
  public_key_cose text not null,
  signature_count bigint not null default 0,
  name            text not null,
  transports      text,
  last_used_at    timestamptz,
  created_at      timestamptz not null,
  created_by      text not null,
  updated_at      timestamptz,
  updated_by      text,
  version         bigint not null default 0
);
create index passkeys_by_user_uid on access.passkeys (user_uid);
create index passkeys_by_user_handle on access.passkeys (user_handle);
grant select, insert, update, delete on access.passkeys to ${application_role};

-- A WebAuthn ceremony in progress: the options sent to the browser, kept until it answers.
create table access.webauthn_ceremonies (
  id           bigint generated always as identity primary key,
  uid          uuid not null unique,
  kind         text not null check (kind in ('REGISTER', 'ASSERT')),
  user_uid     uuid references access.users (uid),
  request_json text not null,
  expires_at   timestamptz not null,
  consumed_at  timestamptz,
  created_at   timestamptz not null,
  created_by   text not null,
  updated_at   timestamptz,
  updated_by   text,
  version      bigint not null default 0
);
grant select, insert, update on access.webauthn_ceremonies to ${application_role};

-- Password reset tokens: SHA-256 hashes, 30 minutes, single use.
create table access.password_resets (
  id           bigint generated always as identity primary key,
  uid          uuid not null unique,
  token_hash   text not null unique,
  user_uid     uuid not null references access.users (uid),
  expires_at   timestamptz not null,
  used_at      timestamptz,
  requested_ip text,
  created_at   timestamptz not null,
  created_by   text not null,
  updated_at   timestamptz,
  updated_by   text,
  version      bigint not null default 0
);
create index password_resets_by_user_uid on access.password_resets (user_uid);
grant select, insert, update on access.password_resets to ${application_role};

-- Invitations to join a tenant. A tenant sees its own; the invitee, who has no session yet, sees
-- exactly the one invitation whose token hash the transaction names in
-- pharmacy.invitation_token_hash. Writes are only ever the current tenant's.
create table access.invitations (
  id             bigint generated always as identity primary key,
  uid            uuid not null unique,
  tenant_uid     uuid not null,
  token_hash     text not null unique,
  email          text not null check (email = lower(email)),
  name           text not null,
  role_id        bigint not null references access.roles (id),
  all_branches   boolean not null,
  branch_uids    text,
  invited_by     text not null,
  expires_at     timestamptz not null,
  accepted_at    timestamptz,
  revoked_at     timestamptz,
  created_at     timestamptz not null,
  created_by     text not null,
  updated_at     timestamptz,
  updated_by     text,
  version        bigint not null default 0
);
create index invitations_by_tenant_uid on access.invitations (tenant_uid, email);
alter table access.invitations enable row level security;
alter table access.invitations force row level security;
create policy tenant_or_token on access.invitations
  using (tenant_uid = platform.current_tenant_uid()
         or token_hash = nullif(current_setting('pharmacy.invitation_token_hash', true), ''))
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on access.invitations to ${application_role};

-- The invitee must read the role named on their invitation before they belong to the tenant.
drop policy tenant_or_own on access.roles;
create policy tenant_or_own on access.roles
  using (
    tenant_uid = platform.current_tenant_uid()
    or exists (
      select 1 from access.memberships m
      where m.role_id = roles.id and m.user_uid = platform.current_user_uid())
    or exists (
      select 1 from access.invitations i
      where i.role_id = roles.id
        and i.token_hash = nullif(current_setting('pharmacy.invitation_token_hash', true), '')))
  with check (tenant_uid = platform.current_tenant_uid());
