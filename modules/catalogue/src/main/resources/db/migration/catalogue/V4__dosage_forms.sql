-- Dosage forms are a closed list (DosageForm); the database now enforces it, including the forms
-- added for oral solutions, sprays, patches, powders, lotions, pessaries, lozenges, eye ointments
-- and infusions.

alter table catalogue.products
  add constraint products_dosage_form_check
  check (dosage_form is null or dosage_form in (
    'TABLET', 'CAPSULE', 'SYRUP', 'SUSPENSION', 'INJECTION', 'INHALER', 'CREAM', 'OINTMENT', 'GEL',
    'DROPS', 'SACHET', 'SUPPOSITORY', 'ORAL_SOLUTION', 'SPRAY', 'PATCH', 'POWDER', 'LOTION',
    'PESSARY', 'LOZENGE', 'EYE_OINTMENT', 'INFUSION', 'DEVICE', 'OTHER'));
