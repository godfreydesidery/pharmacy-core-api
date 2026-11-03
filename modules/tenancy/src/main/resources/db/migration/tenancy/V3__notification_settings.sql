-- Whether a sale to a customer with a phone number sends the receipt by SMS (notifications).
alter table tenancy.settings add column sms_receipts boolean not null default false;
