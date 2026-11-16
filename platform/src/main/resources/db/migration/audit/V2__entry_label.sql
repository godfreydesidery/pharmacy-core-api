-- A human label for the record an entry is about (document number, name, email or code), captured
-- when the entry is written, so the trail reads "Tenant SEED Kilimanjaro" rather than a uid.

alter table audit.entries add column label text;
