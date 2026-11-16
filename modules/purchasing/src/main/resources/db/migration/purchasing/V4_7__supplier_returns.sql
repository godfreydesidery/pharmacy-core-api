-- Supplier returns (debit notes): stock of a batch sent back to its supplier, valued at the
-- batch's cost in the base currency. The ledger reduces accounts payable and inventory by it.

create table purchasing.supplier_returns (
  id                    bigint generated always as identity primary key,
  uid                   uuid not null unique,
  tenant_uid            uuid not null,
  created_at            timestamptz not null,
  created_by            text not null,
  updated_at            timestamptz,
  updated_by            text,
  version               bigint not null default 0,
  number                text not null,
  branch_uid            uuid not null,
  supplier_uid          uuid not null,
  supplier_name         text not null,
  goods_receipt_uid     uuid,
  goods_receipt_number  text,
  reason                text not null,
  note                  text,
  total                 numeric(14,2) not null,
  unique (tenant_uid, number)
);
create index supplier_returns_by_tenant_branch on purchasing.supplier_returns (tenant_uid, branch_uid, id);
alter table purchasing.supplier_returns enable row level security;
alter table purchasing.supplier_returns force row level security;
create policy tenant_isolation on purchasing.supplier_returns
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert on purchasing.supplier_returns to ${application_role};

create table purchasing.supplier_return_lines (
  id                  bigint generated always as identity primary key,
  uid                 uuid not null unique,
  tenant_uid          uuid not null,
  created_at          timestamptz not null,
  created_by          text not null,
  updated_at          timestamptz,
  updated_by          text,
  version             bigint not null default 0,
  supplier_return_id  bigint not null references purchasing.supplier_returns (id),
  product_uid         uuid not null,
  product_name        text not null,
  batch_uid           uuid not null,
  batch_number        text not null,
  expiry_date         date,
  quantity            integer not null check (quantity > 0),
  unit_cost           numeric(14,2) not null,
  line_total          numeric(14,2) not null
);
alter table purchasing.supplier_return_lines enable row level security;
alter table purchasing.supplier_return_lines force row level security;
create policy tenant_isolation on purchasing.supplier_return_lines
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert on purchasing.supplier_return_lines to ${application_role};
