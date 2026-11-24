-- STD-8: adverse drug reaction reports (pharmacovigilance), to be sent to TMDA. The fields follow
-- TMDA's yellow form as far as the sources read show it (verify with TMDA). The patient is optional;
-- their name is replaced when the customer is anonymised (STD-5), the clinical facts stay.
create table inventory.adverse_reactions (
  id                  bigint generated always as identity primary key,
  uid                 uuid not null unique,
  tenant_uid          uuid not null,
  number              text not null,
  branch_uid          uuid not null,
  customer_uid        uuid,
  patient_name        text,
  patient_age         integer check (patient_age between 0 and 150),
  patient_sex         text check (patient_sex in ('FEMALE', 'MALE')),
  patient_weight_kg   numeric(5, 1) check (patient_weight_kg > 0),
  product_uid         uuid not null,
  product_name        text not null,
  batch_number        text,
  dose                text,
  route               text,
  indication          text,
  therapy_started     date,
  therapy_stopped     date,
  reaction            text not null,
  onset_date          date not null,
  seriousness         text not null check (seriousness in ('SERIOUS', 'NOT_SERIOUS')),
  serious_reason      text check (serious_reason in ('DEATH', 'LIFE_THREATENING', 'HOSPITALISATION',
                                                     'DISABILITY', 'CONGENITAL_ANOMALY',
                                                     'OTHER_MEDICALLY_IMPORTANT')),
  outcome             text not null check (outcome in ('RECOVERED', 'RECOVERING', 'NOT_RECOVERED',
                                                       'RECOVERED_WITH_SEQUELAE', 'FATAL', 'UNKNOWN')),
  reporter_name       text not null,
  reporter_profession text,
  reporter_phone      text,
  note                text,
  created_at          timestamptz not null,
  created_by          text not null,
  created_by_uid      uuid,
  updated_at          timestamptz,
  updated_by          text,
  updated_by_uid      uuid,
  version             bigint not null default 0,
  unique (tenant_uid, number),
  check (seriousness = 'SERIOUS' or serious_reason is null)
);
create index adverse_reactions_by_tenant_uid on inventory.adverse_reactions (tenant_uid, uid);
create index adverse_reactions_by_customer
  on inventory.adverse_reactions (tenant_uid, customer_uid) where customer_uid is not null;
alter table inventory.adverse_reactions enable row level security;
alter table inventory.adverse_reactions force row level security;
create policy tenant_isolation on inventory.adverse_reactions
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
-- Update only for the anonymisation of a patient (STD-5); reports are otherwise written once.
grant select, insert, update on inventory.adverse_reactions to ${application_role};
