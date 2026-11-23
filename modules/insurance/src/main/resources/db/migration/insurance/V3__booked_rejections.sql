-- The rejected part of a claim as the books hold it, so that a re-adjudication or a return posts
-- only the change (MON-4, MON-5). Claims adjudicated before this were written off unless the
-- adjudicator charged the patient, which was not recorded on the claim: they count as written off.
alter table insurance.claims add column written_off numeric(14, 2) not null default 0;
alter table insurance.claims add column charged_to_patient numeric(14, 2) not null default 0;

alter table insurance.claims no force row level security;
update insurance.claims
   set written_off = greatest(claimed - approved, 0)
 where approved is not null and status <> 'VOIDED';
alter table insurance.claims force row level security;
