-- FRD-6: the controlled-drugs register lists every product that was ever CONTROLLED, not only those
-- CONTROLLED now. ever_controlled is set when a product is made CONTROLLED, becomes CONTROLLED or
-- stops being so, and never cleared. New column only: existing rows take false, and those
-- CONTROLLED now are listed by their schedule, so no existing row is changed.
alter table catalogue.products add column ever_controlled boolean not null default false;
