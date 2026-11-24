-- STD-3 (adversarial review of 23 Nov 2026): a journal line is a debit or a credit, never both.
-- debit >= 0 and credit >= 0 hold since V1; this adds that at most one of the two is above zero.
--
-- Safe on real data: the constraint is added NOT VALID, so every new line is checked at once while
-- the existing ones are not scanned under a lock. They are then counted (the force on row-level
-- security is lifted for the count, as the owner's queries see no pharmacy's rows otherwise, and
-- put back) and the constraint is validated only when none breaks it. A breaking line is reported
-- as a WARNING naming up to 50 of them (pharmacy, entry number, line uid) instead of failing the
-- release; the constraint then stays NOT VALID, still checking new lines, until those lines are
-- corrected by an adjusting entry and a later migration validates it. VALIDATE takes only a SHARE
-- UPDATE EXCLUSIVE lock: postings go on while it reads.
alter table finance.journal_lines
  add constraint journal_lines_debit_or_credit
  check (not (debit > 0 and credit > 0)) not valid;

alter table finance.journal_lines no force row level security;
alter table finance.journal_entries no force row level security;

do $$
declare
  bad bigint;
  listed text;
begin
  select count(*) into bad from finance.journal_lines where debit > 0 and credit > 0;
  if bad = 0 then
    alter table finance.journal_lines validate constraint journal_lines_debit_or_credit;
  else
    select string_agg(format('%s %s line %s', l.tenant_uid, e.number, l.uid), '; ')
      into listed
      from (select * from finance.journal_lines where debit > 0 and credit > 0
             order by id limit 50) l
      join finance.journal_entries e on e.id = l.entry_id;
    raise warning 'finance.journal_lines: % line(s) are both a debit and a credit; the check'
      ' journal_lines_debit_or_credit holds for new lines but is not validated: %', bad, listed;
  end if;
end
$$;

alter table finance.journal_entries force row level security;
alter table finance.journal_lines force row level security;
