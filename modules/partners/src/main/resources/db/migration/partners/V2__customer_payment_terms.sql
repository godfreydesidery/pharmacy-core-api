-- How long a customer's credit sale may stay unpaid; receivables dates each charge by it.
alter table partners.customers
  add column payment_terms text not null default 'NET_30'
    check (payment_terms in ('COD', 'NET_15', 'NET_30', 'NET_45', 'NET_60'));
