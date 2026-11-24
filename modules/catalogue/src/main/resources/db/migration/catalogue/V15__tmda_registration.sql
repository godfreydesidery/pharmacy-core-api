-- STD-8: a product's TMDA registration (marketing authorisation) number, as printed on the pack
-- and in the TMDA register. Optional: the pharmacy records it when it has it. A nullable column
-- without a default is only a catalogue change (no table rewrite).
alter table catalogue.products add column tmda_registration_number text;
