-- How many minutes without use sign a person out of this pharmacy: 15 by default, as OWASP ASVS
-- and PCI DSS suggest for a shared counter computer; an admin may set 5 to 60 in Settings.

alter table tenancy.settings add column idle_minutes integer not null default 15;
alter table tenancy.settings
  add constraint settings_idle_minutes_range check (idle_minutes between 5 and 60);
