-- Serial-tracked units on a sale (wheelchairs, BP monitors): each sale line records the serial
-- numbers sold and whether each came back, and the warranty end of what it sold.

alter table sales.sale_lines add column warranty_ends_on date;
alter table sales.sale_return_lines add column serial_numbers text;

create table sales.sale_line_serials (
  sale_line_id  bigint not null references sales.sale_lines (id),
  serial_number text not null,
  returned      boolean not null default false,
  primary key (sale_line_id, serial_number)
);
-- A serial row is visible exactly when its sale line is: the line's own policy keeps it to the
-- current tenant.
alter table sales.sale_line_serials enable row level security;
alter table sales.sale_line_serials force row level security;
create policy tenant_isolation on sales.sale_line_serials
  using (exists (select 1 from sales.sale_lines l where l.id = sale_line_id))
  with check (exists (select 1 from sales.sale_lines l where l.id = sale_line_id));
-- Hibernate rewrites an element collection when one element changes: it needs delete.
grant select, insert, update, delete on sales.sale_line_serials to ${application_role};
