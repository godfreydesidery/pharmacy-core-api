-- The owner's controls of 23 Nov 2026, per pharmacy:
-- approval_limit: above it (base currency) a refund, stock write-off, voucher or shift difference
--   needs a manager's approval; TZS 50,000 by default.
-- return_window_days: goods may come back this many days after the sale without a manager; 7.
-- require_open_shift: a cashier opens a till shift before selling or refunding. Off for pharmacies
--   that exist today (their admin turns it on), on for new ones (TenantSettings.defaults).
-- Columns with defaults: existing rows take them; nothing is updated.
alter table tenancy.settings
  add column approval_limit numeric(14,2) not null default 50000 check (approval_limit >= 0),
  add column return_window_days integer not null default 7
    check (return_window_days between 0 and 365),
  add column require_open_shift boolean not null default false;
