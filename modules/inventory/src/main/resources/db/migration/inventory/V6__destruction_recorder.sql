-- The display name of whoever recorded a destruction, for the printed certificate (created_by is
-- the sign-in reference). Older rows have none and show the reference.
alter table inventory.destructions add column created_by_name text;
