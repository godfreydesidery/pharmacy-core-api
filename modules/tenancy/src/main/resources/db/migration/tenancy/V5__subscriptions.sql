-- Subscription plans, each tenant's subscription and its invoices. Like the tenant registry these
-- are platform records read across tenants by the root administrator, so they carry no isolation
-- policy; every read and write names its tenant.

create table tenancy.plans (
  id                bigint generated always as identity primary key,
  uid               uuid not null unique,
  code              text not null unique,
  name              text not null,
  description       text,
  currency          text not null,
  price_monthly     numeric(14,2) not null check (price_monthly >= 0),
  price_yearly      numeric(14,2) not null check (price_yearly >= 0),
  max_branches      integer check (max_branches is null or max_branches >= 1),
  max_users         integer check (max_users is null or max_users >= 1),
  max_products      integer check (max_products is null or max_products >= 1),
  sms_per_month     integer check (sms_per_month is null or sms_per_month >= 0),
  insurance         boolean not null default false,
  multi_currency    boolean not null default false,
  integrations      boolean not null default false,
  advanced_reports  boolean not null default false,
  trial_days        integer not null default 30 check (trial_days >= 0),
  sort_order        integer not null default 0,
  active            boolean not null default true,
  created_at        timestamptz not null,
  created_by        text not null,
  updated_at        timestamptz,
  updated_by        text,
  version           bigint not null default 0
);
grant select, insert, update on tenancy.plans to ${application_role};

create table tenancy.subscriptions (
  id              bigint generated always as identity primary key,
  uid             uuid not null unique,
  tenant_uid      uuid not null unique references tenancy.tenants (uid),
  plan_id         bigint not null references tenancy.plans (id),
  status          text not null
                    check (status in ('TRIAL', 'ACTIVE', 'PAST_DUE', 'SUSPENDED', 'CANCELLED')),
  billing_cycle   text not null check (billing_cycle in ('MONTHLY', 'YEARLY')),
  period_start    date not null,
  period_end      date not null,
  trial_ends      date,
  grace_ends      date,
  last_reminder   text,
  created_at      timestamptz not null,
  created_by      text not null,
  updated_at      timestamptz,
  updated_by      text,
  version         bigint not null default 0
);
grant select, insert, update on tenancy.subscriptions to ${application_role};

create sequence tenancy.subscription_invoice_numbers;
grant usage on sequence tenancy.subscription_invoice_numbers to ${application_role};

create table tenancy.subscription_invoices (
  id                 bigint generated always as identity primary key,
  uid                uuid not null unique,
  number             text not null unique,
  tenant_uid         uuid not null references tenancy.tenants (uid),
  subscription_id    bigint not null references tenancy.subscriptions (id),
  plan_code          text not null,
  plan_name          text not null,
  billing_cycle      text not null,
  period_start       date not null,
  period_end         date not null,
  amount             numeric(14,2) not null check (amount >= 0),
  currency           text not null,
  status             text not null check (status in ('OPEN', 'PAID', 'VOID')),
  due_date           date not null,
  paid_at            date,
  payment_method     text,
  payment_reference  text,
  created_at         timestamptz not null,
  created_by         text not null,
  updated_at         timestamptz,
  updated_by         text,
  version            bigint not null default 0
);
create index subscription_invoices_by_tenant on tenancy.subscription_invoices (tenant_uid, id);
grant select, insert, update on tenancy.subscription_invoices to ${application_role};

insert into tenancy.plans
  (uid, code, name, description, currency, price_monthly, price_yearly, max_branches, max_users,
   max_products, sms_per_month, insurance, multi_currency, integrations, advanced_reports,
   trial_days, sort_order, created_at, created_by)
values
  ('01a1e000-0000-7000-8000-000000000001', 'STARTER', 'Starter',
   'One pharmacy counter: sales, stock, purchasing and customers.', 'TZS', 50000, 500000,
   1, 3, 500, 200, false, false, false, false, 30, 1, now(), 'migration'),
  ('01a1e000-0000-7000-8000-000000000002', 'STANDARD', 'Standard',
   'A growing pharmacy: a few branches, insurance claims and more staff.', 'TZS', 120000, 1200000,
   3, 15, null, 1000, true, false, false, false, 30, 2, now(), 'migration'),
  ('01a1e000-0000-7000-8000-000000000003', 'PRO', 'Pro',
   'Chains: unlimited branches, multiple currencies, integrations and full financial reports.',
   'TZS', 250000, 2500000, null, null, null, 5000, true, true, true, true, 30, 3, now(),
   'migration');

-- Pharmacies registered before plans existed keep everything they had: Pro, already active.
insert into tenancy.subscriptions
  (uid, tenant_uid, plan_id, status, billing_cycle, period_start, period_end, created_at,
   created_by)
select gen_random_uuid(), t.uid, (select id from tenancy.plans where code = 'PRO'), 'ACTIVE',
       'MONTHLY', current_date, (current_date + interval '1 month')::date, now(), 'migration'
from tenancy.tenants t;
