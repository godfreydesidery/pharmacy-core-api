-- integrations: outbound webhooks and the record of every delivery.

grant usage on schema integrations to ${application_role};

-- event_types is the comma-joined list of subscribed event types, such as
-- sale.completed,goods.received. The secret signs every delivery (HMAC-SHA256).
create table integrations.webhooks (
  id          bigint generated always as identity primary key,
  uid         uuid not null unique,
  tenant_uid  uuid not null,
  name        text not null,
  url         text not null,
  secret      text not null,
  event_types text not null,
  active      boolean not null default true,
  created_at  timestamptz not null,
  created_by  text not null,
  updated_at  timestamptz,
  updated_by  text,
  version     bigint not null default 0
);
create index webhooks_by_tenant on integrations.webhooks (tenant_uid);
alter table integrations.webhooks enable row level security;
alter table integrations.webhooks force row level security;
create policy tenant_isolation on integrations.webhooks
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update, delete on integrations.webhooks to ${application_role};

-- One event to one webhook; (tenant, webhook, event) is unique, so a redelivered event is not
-- sent twice. A deleted webhook's deliveries stay as its history.
create table integrations.deliveries (
  id              bigint generated always as identity primary key,
  uid             uuid not null unique,
  tenant_uid      uuid not null,
  webhook_uid     uuid not null,
  event_type      text not null,
  event_id        uuid not null,
  payload         text not null,
  status          text not null check (status in ('PENDING', 'DELIVERED', 'FAILED')),
  attempts        integer not null default 0,
  response_status integer,
  last_error      text,
  delivered_at    timestamptz,
  created_at      timestamptz not null,
  created_by      text not null,
  updated_at      timestamptz,
  updated_by      text,
  version         bigint not null default 0,
  unique (tenant_uid, webhook_uid, event_id)
);
create index deliveries_by_webhook on integrations.deliveries (webhook_uid, uid);
alter table integrations.deliveries enable row level security;
alter table integrations.deliveries force row level security;
create policy tenant_isolation on integrations.deliveries
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on integrations.deliveries to ${application_role};
