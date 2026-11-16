-- Days a supplier takes to deliver after an order; reorder suggestions cover this gap.

alter table partners.suppliers
  add column lead_time_days integer not null default 7
    check (lead_time_days between 0 and 365);
