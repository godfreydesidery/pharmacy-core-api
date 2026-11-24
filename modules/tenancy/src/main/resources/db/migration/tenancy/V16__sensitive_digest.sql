-- FRD-19 (adversarial review 23 Nov 2026): each morning the pharmacy's owners and admins get a
-- digest of the day before's sensitive actions (refunds and returns, write-offs, adjustments and
-- destructions, approvals granted, role and permission changes, period unlocks, vouchers, deep
-- discounts, medicine class changes, debts written off), in the app and by email. On unless the
-- pharmacy turns it off in Settings. A constant default: no table rewrite.
alter table tenancy.settings add column sensitive_digest boolean not null default true;
