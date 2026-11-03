-- fx: exchange rates per tenant and day. A rate is units of quote per 1 base. Documents in a
-- foreign currency store the rate they used, so a later rate never changes a posted amount.

grant usage on schema fx to ${application_role};

create table fx.exchange_rates (
  id           bigint generated always as identity primary key,
  uid          uuid not null unique,
  tenant_uid   uuid not null,
  base         text not null check (base ~ '^[A-Z]{3}$'),
  quote        text not null check (quote ~ '^[A-Z]{3}$' and quote <> base),
  rate         numeric(24, 10) not null check (rate > 0),
  effective_on date not null,
  source       text not null,
  created_at   timestamptz not null,
  created_by   text not null,
  updated_at   timestamptz,
  updated_by   text,
  version      bigint not null default 0,
  unique (tenant_uid, base, quote, effective_on, source)
);
create index exchange_rates_by_pair_day
  on fx.exchange_rates (tenant_uid, base, quote, effective_on desc, id desc);
alter table fx.exchange_rates enable row level security;
alter table fx.exchange_rates force row level security;
create policy tenant_isolation on fx.exchange_rates
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on fx.exchange_rates to ${application_role};
