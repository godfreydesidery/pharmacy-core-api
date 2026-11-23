-- Needs ACCESS EXCLUSIVE on lockprobe.busy: waits while another transaction reads the table.
alter table lockprobe.busy add column waited int;
