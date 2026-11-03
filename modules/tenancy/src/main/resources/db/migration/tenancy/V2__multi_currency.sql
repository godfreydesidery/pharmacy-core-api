-- Multi-currency: the foreign currencies a tenant accepts besides its base (the tenant's currency),
-- as ISO codes joined by commas, such as 'USD,KES'. Null means the base only.
alter table tenancy.settings add column accepted_currencies text
  check (accepted_currencies is null or accepted_currencies ~ '^[A-Z]{3}(,[A-Z]{3})*$');
