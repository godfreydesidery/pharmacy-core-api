-- MigrationModesTest: what a migration connection is set to, and a table to wait on.
create table lockprobe.settings as
  select current_setting('lock_timeout') as lock_timeout,
         current_setting('statement_timeout') as statement_timeout;
create table lockprobe.busy (id int);
