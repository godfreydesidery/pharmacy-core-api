-- Receipts of products without expiry (supplies, equipment) and of serial-tracked units
-- (wheelchairs): the expiry becomes optional and a line records its units' serial numbers.

alter table purchasing.goods_receipt_lines alter column expiry_date drop not null;
alter table purchasing.goods_receipt_lines add column serial_numbers text;
