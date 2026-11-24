-- STD-19: branches' and pharmacies' contact phones in E.164, as the API now keeps every phone it is
-- given (tz.co.pharmacy.platform.kernel.PhoneNumbers): a local number takes the pharmacy's country
-- code. A value the rules cannot read is left exactly as it was (the API keeps it while it is sent
-- back unchanged).
--
-- Migrations run as the tables' owner, whom forced row-level security would hold to no tenant:
-- tenancy.branches forces it, so it is lifted while the rows are updated, and restored;
-- tenancy.tenants forces none.

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

alter table tenancy.branches no force row level security;

update tenancy.branches b
   set phone = n.e164
  from (select x.id, pg_temp.phone_e164(x.phone, pg_temp.calling_code(t.country)) as e164
          from tenancy.branches x
          left join tenancy.tenants t on t.uid = x.tenant_uid
         where x.phone is not null) n
 where b.id = n.id
   and n.e164 is not null
   and n.e164 <> b.phone;

alter table tenancy.branches force row level security;

update tenancy.tenants t
   set contact_phone = pg_temp.phone_e164(t.contact_phone, pg_temp.calling_code(t.country))
 where t.contact_phone is not null
   and pg_temp.phone_e164(t.contact_phone, pg_temp.calling_code(t.country)) is not null
   and pg_temp.phone_e164(t.contact_phone, pg_temp.calling_code(t.country)) <> t.contact_phone;

drop function pg_temp.phone_e164(text, text);
drop function pg_temp.calling_code(text);
