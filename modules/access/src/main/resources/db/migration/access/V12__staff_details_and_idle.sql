-- G15: what a pharmacy keeps about the people who work for it, its own and no one else's: a staff
-- number (unique in the pharmacy), a job title and a work phone and email. They sit on the
-- membership, so they never touch the person's own account or reach another pharmacy.
alter table access.memberships
  add column staff_number text,
  add column job_title    text,
  add column work_phone   text,
  add column work_email   text;
create unique index memberships_staff_number
  on access.memberships (tenant_uid, lower(staff_number)) where staff_number is not null;

-- G17: a session takes the strictest inactivity limit of the pharmacies it works in, instead of
-- the last one a tab reported. idle_pinned: a pharmacy has set the limit (the platform's default
-- applies until one does).
alter table access.sessions add column idle_pinned boolean not null default false;
