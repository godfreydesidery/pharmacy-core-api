-- Runs once, as the postgres superuser, when the Docker volume is first created.
-- pharmacy_owner owns the database and the schemas and runs the migrations.
-- pharmacy_app is what the API connects as: no superuser, no RLS bypass, owns nothing.
create role pharmacy_owner login password 'local-owner-password';
create role pharmacy_app login nosuperuser nobypassrls password 'local-app-password';
create database pharmacy owner pharmacy_owner;
