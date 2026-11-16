-- Payments: the platform's registry of payment methods (what any pharmacy may accept and through
-- which adapter), each pharmacy's enabled methods with its own merchant details, and payment
-- requests pushed to a customer's phone (M-Pesa, Mixx by Yas, Airtel Money, an aggregator).

-- The registry is platform data, not a tenant's: it carries no isolation policy.
create table integrations.payment_method_types (
  id                  bigint generated always as identity primary key,
  uid                 uuid not null unique,
  code                text not null unique,
  name                text not null,
  kind                text not null check (kind in ('CASH', 'MOBILE_MONEY', 'BANK_TRANSFER', 'CARD',
                                                    'CHEQUE', 'VOUCHER')),
  adapter             text not null check (adapter in ('NONE', 'MANUAL', 'SIMULATOR', 'MPESA', 'MIXX',
                                                       'AIRTEL', 'SELCOM', 'AZAMPAY')),
  requires_reference  boolean not null default false,
  enabled             boolean not null default true,
  sort_order          integer not null default 0,
  created_at          timestamptz not null,
  created_by          text not null,
  updated_at          timestamptz,
  updated_by          text,
  version             bigint not null default 0
);
grant select, insert, update on integrations.payment_method_types to ${application_role};

insert into integrations.payment_method_types
  (uid, code, name, kind, adapter, requires_reference, enabled, sort_order, created_at, created_by)
values
  (gen_random_uuid(), 'CASH', 'Cash', 'CASH', 'NONE', false, true, 0, now(), 'system'),
  (gen_random_uuid(), 'MPESA', 'M-Pesa (Vodacom)', 'MOBILE_MONEY', 'MPESA', true, true, 10, now(), 'system'),
  (gen_random_uuid(), 'MIXX', 'Mixx by Yas (Tigo Pesa)', 'MOBILE_MONEY', 'MIXX', true, true, 11, now(), 'system'),
  (gen_random_uuid(), 'AIRTEL_MONEY', 'Airtel Money', 'MOBILE_MONEY', 'AIRTEL', true, true, 12, now(), 'system'),
  (gen_random_uuid(), 'SELCOM', 'Selcom (aggregator)', 'MOBILE_MONEY', 'SELCOM', true, true, 13, now(), 'system'),
  (gen_random_uuid(), 'AZAMPAY', 'AzamPay (aggregator)', 'MOBILE_MONEY', 'AZAMPAY', true, true, 14, now(), 'system'),
  (gen_random_uuid(), 'MOBILE_OTHER', 'Mobile money (other)', 'MOBILE_MONEY', 'MANUAL', false, true, 19, now(), 'system'),
  (gen_random_uuid(), 'BANK_TRANSFER', 'Bank transfer / deposit', 'BANK_TRANSFER', 'MANUAL', true, true, 20, now(), 'system'),
  (gen_random_uuid(), 'CARD', 'Card (POS terminal)', 'CARD', 'MANUAL', false, true, 30, now(), 'system'),
  (gen_random_uuid(), 'CHEQUE', 'Cheque', 'CHEQUE', 'MANUAL', true, true, 40, now(), 'system'),
  (gen_random_uuid(), 'VOUCHER', 'Voucher / gift card', 'VOUCHER', 'NONE', true, true, 50, now(), 'system'),
  (gen_random_uuid(), 'SIMULATOR', 'Test push payment (simulator)', 'MOBILE_MONEY', 'SIMULATOR', true, false, 90, now(), 'system');

-- A method a pharmacy accepts: its label, its merchant details (paybill, till, bank account) and,
-- for its own provider account, its credentials (sealed with the platform key).
create table integrations.tenant_payment_methods (
  id                  bigint generated always as identity primary key,
  uid                 uuid not null unique,
  tenant_uid          uuid not null,
  method_code         text not null,
  label               text,
  account_reference   text,
  sealed_credentials  text,
  enabled             boolean not null default true,
  created_at          timestamptz not null,
  created_by          text not null,
  updated_at          timestamptz,
  updated_by          text,
  version             bigint not null default 0
);
create unique index tenant_payment_methods_unique
  on integrations.tenant_payment_methods (tenant_uid, method_code);
alter table integrations.tenant_payment_methods enable row level security;
alter table integrations.tenant_payment_methods force row level security;
create policy tenant_isolation on integrations.tenant_payment_methods
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update, delete on integrations.tenant_payment_methods to ${application_role};

-- A payment request pushed to a customer's phone, confirmed (or not) by the provider.
create table integrations.payment_intents (
  id                 bigint generated always as identity primary key,
  uid                uuid not null unique,
  tenant_uid         uuid not null,
  branch_uid         uuid,
  method_code        text not null,
  amount             numeric(14,2) not null check (amount > 0),
  currency           text not null,
  phone              text,
  status             text not null check (status in ('PENDING', 'CONFIRMED', 'FAILED', 'EXPIRED',
                                                     'USED')),
  provider_reference text,
  failure_reason     text,
  sale_number        text,
  expires_at         timestamptz not null,
  created_at         timestamptz not null,
  created_by         text not null,
  updated_at         timestamptz,
  updated_by         text,
  version            bigint not null default 0
);
alter table integrations.payment_intents enable row level security;
alter table integrations.payment_intents force row level security;
create policy tenant_isolation on integrations.payment_intents
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on integrations.payment_intents to ${application_role};
