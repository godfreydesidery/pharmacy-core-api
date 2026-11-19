-- How the web app looks for everyone in the pharmacy: PREMIUM (the modern look) or CLASSIC (a
-- Windows 2000 style: grey bevelled buttons, sunken fields, blue title bars). Every pharmacy keeps
-- the look it has; an admin changes it in Settings.

alter table tenancy.settings add column look varchar(16) not null default 'PREMIUM';
alter table tenancy.settings add constraint settings_look_known check (look in ('PREMIUM', 'CLASSIC'));
