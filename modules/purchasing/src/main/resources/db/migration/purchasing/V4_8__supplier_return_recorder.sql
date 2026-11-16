-- The display name of whoever recorded a return, for the printed debit note (created_by is the
-- sign-in reference). Older rows have none and show the reference.
alter table purchasing.supplier_returns add column created_by_name text;
