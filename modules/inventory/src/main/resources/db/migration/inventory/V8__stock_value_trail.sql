-- MON-6 and MON-9. New columns only; no existing row is changed.
--  * movements.unit_cost: the batch's unit cost once the movement was made, so a return can be
--    valued as it was restocked and the difference from what the books took back be posted.
--    Movements are append-only: rows from before stay null.
--  * stock_count_lines.moved: the batch's net movement at the branch between the count sheet and
--    its posting; the posted correction is counted - (expected + moved).
alter table inventory.movements add column unit_cost numeric(14, 2);
alter table inventory.stock_count_lines add column moved integer;
