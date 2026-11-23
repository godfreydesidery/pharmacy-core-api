-- The branch a voucher was sold at, so the cash paid for it counts in that cashier's shift.
-- Vouchers sold before this have none and count in no shift.
alter table sales.vouchers add column branch_uid uuid;
create index vouchers_by_branch_created on sales.vouchers (tenant_uid, branch_uid, created_at);
