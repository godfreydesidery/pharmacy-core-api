-- STD-19: people's phones by the API's one set of rules (tz.co.pharmacy.platform.kernel.
-- PhoneNumbers). A person's phone was already E.164 in form (a check since V4_1); what changes is
-- "+2550754000111", the trunk 0 typed after the country code, which becomes "+255754000111". A
-- person's phone takes Tanzania's code (an account belongs to no one pharmacy); a staff member's
-- work phone and the details kept when they left take their pharmacy's. A value the rules cannot
-- read is left exactly as it was, and a phone another account has verified is never taken.
--
-- access.users forces no row-level security; access.memberships does, so it is lifted while its
-- rows are updated, and restored. tenancy.tenants (the pharmacy's country) migrates first.

-- The rules of tz.co.pharmacy.platform.kernel.PhoneNumbers, for this migration only (pg_temp).
create or replace function pg_temp.calling_code(country text) returns text
  language sql immutable as $$
  select case upper(coalesce(country, 'TZ'))
    when 'TZ' then '255' when 'KE' then '254' when 'UG' then '256' when 'RW' then '250'
    when 'BI' then '257' when 'CD' then '243' when 'MZ' then '258' when 'ZM' then '260'
    when 'MW' then '265' when 'ZA' then '27' when 'ET' then '251' when 'SS' then '211'
    when 'SO' then '252' when 'AE' then '971' when 'IN' then '91' when 'CN' then '86'
    when 'GB' then '44' when 'US' then '1' else '255' end
$$;

create or replace function pg_temp.phone_e164(raw text, code text) returns text
  language plpgsql immutable as $$
declare
  p text := regexp_replace(coalesce(raw, ''), '[[:space:]().-]', '', 'g');
begin
  if p = '' then
    return null;
  end if;
  if left(p, 2) = '00' then
    p := '+' || substr(p, 3);
  end if;
  if left(p, 1) = '+' then
    -- "+255 (0)754...": the national trunk 0 written after the country code.
    if left(p, length(code) + 2) = '+' || code || '0' then
      p := '+' || code || substr(p, length(code) + 3);
    end if;
  elsif p ~ '^0[0-9]+$' then
    p := '+' || code || substr(p, 2);
  elsif p ~ '^[0-9]+$' and left(p, length(code)) = code and length(p) > length(code) + 6 then
    p := '+' || p;
  elsif code = '255' and p ~ '^[67][0-9]{8}$' then
    p := '+255' || p;
  else
    return null;
  end if;
  if p !~ '^\+[1-9][0-9]{7,14}$' then
    return null;
  end if;
  if left(p, 4) in ('+255', '+254', '+256', '+250') and length(p) <> 13 then
    return null;
  end if;
  return p;
end
$$;

update access.users u
   set phone = n.e164
  from (select x.id, pg_temp.phone_e164(x.phone, '255') as e164
          from access.users x
         where x.phone is not null) n
 where u.id = n.id
   and n.e164 is not null
   and n.e164 <> u.phone
   and not exists (select 1
                     from access.users o
                    where o.phone = n.e164
                      and o.phone_verified_at is not null
                      and o.id <> u.id);

alter table access.memberships no force row level security;

update access.memberships m
   set work_phone = coalesce(n.work, m.work_phone),
       former_phone = coalesce(n.former, m.former_phone)
  from (select x.id,
               pg_temp.phone_e164(x.work_phone, pg_temp.calling_code(t.country)) as work,
               pg_temp.phone_e164(x.former_phone, pg_temp.calling_code(t.country)) as former
          from access.memberships x
          left join tenancy.tenants t on t.uid = x.tenant_uid
         where x.work_phone is not null or x.former_phone is not null) n
 where m.id = n.id
   and (n.work <> m.work_phone or n.former <> m.former_phone);

alter table access.memberships force row level security;

drop function pg_temp.phone_e164(text, text);
drop function pg_temp.calling_code(text);
