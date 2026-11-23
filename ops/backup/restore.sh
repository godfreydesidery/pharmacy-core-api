#!/usr/bin/env bash
# Restores a Pharmacy Core logical backup into a NEW database, then checks it.
#
#   restore.sh <backup>          <backup>: a .dump, a .dump.gpg, or the s3:// URI of a .dump.gpg
#   restore.sh --checks-only     the checks alone, on RESTORE_DB (for instance again as another role)
#
# Never into the live database: it refuses a RESTORE_DB that exists. Point the API at the new one
# only after the checks pass (docs/runbook/backup-restore.md).
#
# TARGET SERVER: the libpq environment, nothing on the command line:
#   PGHOST, PGPORT, PGUSER (a superuser, or the RDS master user), PGPASSWORD or PGPASSFILE,
#   PGSSLMODE. PGDATABASE: the maintenance database to connect to first (default postgres).
# REQUIRED
#   RESTORE_DB              the database to create
# OPTIONAL
#   RESTORE_OWNER           the owner role of the schemas (default pharmacy_owner)
#   RESTORE_APP_ROLE        the application role (default pharmacy_app)
#   RESTORE_APP_PASSWORD    its password, for the login check (else libpq's .pgpass or trust)
#   RESTORE_JOBS            parallel restore jobs (default 4)
#   RESTORE_MANIFEST        the backup's manifest (a file or s3://) to compare Flyway versions
#                           with; default: the one next to an s3:// backup or a local file
#   RESTORE_COMPARE_DSN     a libpq connection string of the source database: compare the row
#                           count of every table (exact for a quiet source, as in a drill)
#   GNUPGHOME               the keyring holding the backup's private key (for .gpg)
#
# CHECKS (exit status 1 when any fails):
#   1. Flyway: every schema's history has no failed migration; versions match the manifest.
#   2. Rows: the exact row count of every table, summed per schema (and equal to the source's).
#   3. Row-level security: every table with a tenant_uid column has RLS enabled AND forced, and
#      the policies are there.
#   4. The application role logs in, is neither superuser nor BYPASSRLS, sees no tenant row
#      without a tenant (fail closed) and sees that tenant's rows with one.
set -euo pipefail
umask 077

log() { printf '%s restore: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2; }
fail() { log "FAILED: $*"; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || fail "$1 is not on the PATH"; }

need psql
need pg_restore
[[ -n "${RESTORE_DB:-}" ]] || fail "RESTORE_DB is not set"
[[ "$RESTORE_DB" =~ ^[a-z_][a-z0-9_]*$ ]] || fail "RESTORE_DB must be a plain lower-case name"
owner="${RESTORE_OWNER:-pharmacy_owner}"
app="${RESTORE_APP_ROLE:-pharmacy_app}"
admin_db="${PGDATABASE:-postgres}"
started=$(date +%s)
mark=$started
step() { local now; now=$(date +%s); log "$1 ($((now - mark)) s)"; mark=$now; }

# psql, unaligned; carriage returns dropped (psql on Windows ends lines with CRLF).
q() { psql -X -q -A -t -v ON_ERROR_STOP=1 "$@" | tr -d '\r'; }
qa() { q -d "$admin_db" "$@"; }
qr() { q -d "$RESTORE_DB" "$@"; }

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

checks_only=false
if [[ "${1:-}" == "--checks-only" ]]; then
  checks_only=true
elif [[ $# -ne 1 ]]; then
  sed -n '2,12p' "$0" >&2
  exit 2
fi

# ------------------------------------------------------------------------------------- restore
if [[ "$checks_only" == "false" ]]; then
  src="$1"
  manifest="${RESTORE_MANIFEST:-}"
  if [[ "$src" == s3://* ]]; then
    need aws
    aws s3 cp "$src" "$work/" --only-show-errors || fail "cannot download $src"
    aws s3 cp "$src.sha256" "$work/" --only-show-errors || fail "cannot download $src.sha256"
    [[ -n "$manifest" ]] || manifest="${src%.dump.gpg}.manifest.json"
    file="$work/$(basename "$src")"
  else
    [[ -f "$src" ]] || fail "no such file: $src"
    file="$src"
    [[ -n "$manifest" || ! -f "${src%.dump.gpg}.manifest.json" ]] || manifest="${src%.dump.gpg}.manifest.json"
  fi
  if [[ -f "$file.sha256" ]]; then
    need sha256sum
    (cd "$(dirname "$file")" && sha256sum --quiet -c "$(basename "$file").sha256") \
      || fail "the backup's SHA-256 does not match: do not use it"
    step "checksum ok"
  else
    log "no .sha256 next to the backup: integrity not checked"
  fi
  if [[ -n "$manifest" && "$manifest" == s3://* ]]; then
    aws s3 cp "$manifest" "$work/manifest.json" --only-show-errors || fail "cannot download $manifest"
    manifest="$work/manifest.json"
  fi

  dump="$file"
  if [[ "$file" == *.gpg ]]; then
    need gpg
    dump="$work/backup.dump"
    gpg --batch --quiet --output "$dump" --decrypt "$file" 2>/dev/null \
      || fail "gpg could not decrypt (is the backup private key in GNUPGHOME?)"
    step "decrypted"
  fi
  entries=$(pg_restore --list "$dump" | grep -cE '^[0-9]+;' || true)
  (( entries > 0 )) || fail "not a pg_dump custom-format archive"

  # The roles the dump grants to must exist first (roles are not in a database dump).
  [[ -z "$(qa -c "select 1 from pg_database where datname = '$RESTORE_DB'")" ]] \
    || fail "database $RESTORE_DB exists: restore into a new database"
  grantees=$(pg_restore --schema-only --file=- "$dump" \
    | sed -nE 's/^(GRANT|REVOKE) .* (TO|FROM) (.*);$/\3/p' | tr ',' '\n' \
    | sed -E 's/^ +//; s/ +$//; s/"//g; s/ WITH GRANT OPTION//' | grep -v '^PUBLIC$' | sort -u || true)
  for role in "$owner" "$app" $grantees; do
    [[ -n "$(qa -c "select 1 from pg_roles where rolname = '$role'")" ]] && continue
    case "$role" in
      "$app") qa -c "create role \"$role\" login nosuperuser nobypassrls" ;;
      "$owner") qa -c "create role \"$role\" login" ;;
      *) qa -c "create role \"$role\" nologin" ;;
    esac
    log "created role $role (no password: set one before anything connects as it)"
  done
  if [[ "$(qa -c "select rolsuper from pg_roles where rolname = current_user")" != "t" ]]; then
    # The RDS master user is no superuser: it must be a member to create a database owned by the
    # owner role and to restore as it (SET ROLE).
    qa -c "grant \"$owner\" to current_user" >/dev/null
  fi
  qa -c "create database \"$RESTORE_DB\" owner \"$owner\" template template0 encoding 'UTF8'" >/dev/null
  step "database $RESTORE_DB created for $owner"

  # Everything is created as the owner role, so ownership is as in production whoever restores.
  # Two passes: forced row-level security binds the owner too, and once a table's RLS is on, the
  # check of a foreign key that pg_restore adds afterwards is refused ("query would be affected by
  # row-level security policy"; parallel jobs may enable it first, as Drill 1 found). So first
  # everything but RLS and the policies, then RLS and the policies, the last thing restored.
  pg_restore --list "$dump" >"$work/toc"
  rls_entries='^[0-9]+; [0-9]+ [0-9]+ (ROW SECURITY|POLICY) '
  grep -E "$rls_entries" "$work/toc" >"$work/toc.rls" || true
  grep -vE "$rls_entries" "$work/toc" >"$work/toc.main"
  pg_restore --no-owner --role="$owner" --exit-on-error --jobs="${RESTORE_JOBS:-4}" \
    --use-list="$work/toc.main" --dbname="$RESTORE_DB" "$dump" \
    || fail "pg_restore stopped on an error (the database is incomplete: drop it)"
  step "restored $(grep -cE '^[0-9]+;' "$work/toc.main") archive entries (tables, data, keys, grants)"
  pg_restore --no-owner --role="$owner" --exit-on-error --single-transaction \
    --use-list="$work/toc.rls" --dbname="$RESTORE_DB" "$dump" \
    || fail "pg_restore could not restore row-level security (the database is unsafe: drop it)"
  step "restored $(grep -cE '^[0-9]+;' "$work/toc.rls") row-level security entries and policies"
  qr -c "analyze" >/dev/null
  step "analyzed"
fi

# -------------------------------------------------------------------------------------- checks
problems=0
problem() { log "CHECK FAILED: $*"; problems=$((problems + 1)); }

sees_all=$(qr -c "select (rolsuper or rolbypassrls)::text from pg_roles where rolname = current_user")

# 1. Flyway
flyway=$(qr -F ' ' -c "select h.table_schema from information_schema.tables h
  where h.table_name = 'flyway_schema_history' order by 1")
[[ -n "$flyway" ]] || problem "no flyway_schema_history table"
printf '\n%-16s %-10s %s\n' "schema" "version" "failed" >&2
for schema in $flyway; do
  line=$(qr -F ' ' -c "select coalesce((select version from \"$schema\".flyway_schema_history
    where success and version is not null order by installed_rank desc limit 1), '-'),
    (select count(*) from \"$schema\".flyway_schema_history where not success)")
  read -r version failed <<<"$line"
  printf '%-16s %-10s %s\n' "$schema" "$version" "$failed" >&2
  (( failed == 0 )) || problem "$schema has $failed failed migrations"
  if [[ -n "${manifest:-}" && -f "${manifest:-}" ]]; then
    expected=$(grep -o "\"$schema\":\"[^\"]*\"" "$manifest" | head -1 | cut -d'"' -f4 || true)
    [[ -z "$expected" || "$expected" == "$version" ]] \
      || problem "$schema is at version $version, the backup's manifest says $expected"
  fi
done
step "flyway checked"

# 2. Rows
count_sql="select n.nspname || ' ' || c.relname || ' ' ||
    (xpath('/row/c/text()', query_to_xml(format('select count(*) as c from %I.%I', n.nspname,
      c.relname), false, true, '')))[1]::text
  from pg_class c join pg_namespace n on n.oid = c.relnamespace
  where c.relkind in ('r', 'p') and not c.relispartition
    and n.nspname not in ('pg_catalog', 'information_schema') and n.nspname not like 'pg_toast%'
  order by 1"
qr -c "$count_sql" >"$work/rows.restored"
printf '\n%-16s %8s %12s\n' "schema" "tables" "rows" >&2
awk '{t[$1]++; r[$1]+=$3} END {for (s in t) printf "%-16s %8d %12d\n", s, t[s], r[s]}' \
  "$work/rows.restored" | sort >&2
total_rows=$(awk '{r+=$3} END {print r+0}' "$work/rows.restored")
total_tables=$(wc -l <"$work/rows.restored" | tr -d ' ')
[[ "$sees_all" == "true" ]] \
  || problem "$(qr -c 'select current_user') cannot bypass RLS: tenant tables count 0 here; rerun --checks-only as a superuser or pharmacy_backup"
if [[ -n "${RESTORE_COMPARE_DSN:-}" ]]; then
  q "$RESTORE_COMPARE_DSN" -c "$count_sql" >"$work/rows.source" \
    || problem "cannot count the source's rows"
  if ! diff "$work/rows.source" "$work/rows.restored" >"$work/rows.diff"; then
    problem "$(grep -c '^[<>]' "$work/rows.diff") table row counts differ from the source:"
    head -40 "$work/rows.diff" >&2
  else
    log "every table's row count equals the source's ($total_tables tables, $total_rows rows)"
  fi
fi
step "rows counted: $total_tables tables, $total_rows rows"

# 3. Row-level security: the same tables as at the source carry it, enabled and forced. A few
# platform-scope tables name a tenant without belonging to one and have none (as
# RowLevelSecurityTest lists them); the manifest records the list the backup saw.
rls_sql="select n.nspname || '.' || c.relname || ' ' || c.relrowsecurity::text || ' ' ||
    c.relforcerowsecurity::text
  from pg_class c join pg_namespace n on n.oid = c.relnamespace
  where c.relkind in ('r', 'p') and n.nspname not in ('pg_catalog', 'information_schema')
    and exists (select 1 from pg_attribute a where a.attrelid = c.oid
      and a.attname = 'tenant_uid' and not a.attisdropped and a.attnum > 0)
  order by 1"
qr -c "$rls_sql" >"$work/rls.restored"
exempt="${RESTORE_RLS_EXEMPT:-}"
if [[ -z "$exempt" && -n "${manifest:-}" && -f "${manifest:-}" ]]; then
  exempt=$(sed -nE 's/.*"tenantTablesWithoutRls": *"([^"]*)".*/\1/p' "$manifest" | tr ',' ' ')
fi
exempt="${exempt:-tenancy.subscriptions tenancy.subscription_invoices access.session_pharmacies}"
tenant_tables=$(wc -l <"$work/rls.restored" | tr -d ' ')
awk '$2 == "true" && $3 == "true" {print $1}' "$work/rls.restored" >"$work/rls.forced"
forced=$(wc -l <"$work/rls.forced" | tr -d ' ')
policies=$(qr -c "select count(*) from pg_policies")
unprotected=$(awk '!($2 == "true" && $3 == "true") {print $1}' "$work/rls.restored" \
  | grep -vxF -f <(tr ' ' '\n' <<<"$exempt") | tr '\n' ' ' || true)
log "row-level security: $forced of $tenant_tables tenant tables enabled and forced, $policies policies; exempt by design: $exempt"
(( tenant_tables > 0 )) || problem "no table has a tenant_uid column"
[[ -z "$unprotected" ]] || problem "tenant tables without forced RLS: $unprotected"
(( policies >= forced )) || problem "only $policies policies for $forced tables with forced RLS"
if [[ -n "${manifest:-}" && -f "${manifest:-}" ]]; then
  expected=$(sed -nE 's/.*"rlsForcedTables": *([0-9]+).*/\1/p' "$manifest")
  [[ -z "$expected" || "$expected" == "$forced" ]] \
    || problem "$forced tables have forced RLS, the backup's manifest says $expected"
fi
if [[ -n "${RESTORE_COMPARE_DSN:-}" ]]; then
  q "$RESTORE_COMPARE_DSN" -c "$rls_sql" >"$work/rls.source"
  diff -q "$work/rls.source" "$work/rls.restored" >/dev/null \
    || problem "the RLS flags differ from the source's: $(diff "$work/rls.source" "$work/rls.restored" | head -10)"
fi
step "row-level security checked"

# 4. The application role, probed on the tenant table with forced RLS that has the most rows
probe=$(awk 'NR == FNR {forced[$1]; next} ($1 "." $2) in forced {print $3, $1 "." $2}' \
  "$work/rls.forced" "$work/rows.restored" | sort -rn | awk '$1 > 0 {print $2; exit}')
app_q() {
  if [[ -n "${RESTORE_APP_PASSWORD:-}" ]]; then
    PGPASSWORD="$RESTORE_APP_PASSWORD" q -U "$app" -d "$RESTORE_DB" "$@"
  else
    env -u PGPASSWORD psql -X -q -A -t -v ON_ERROR_STOP=1 -U "$app" -d "$RESTORE_DB" "$@" \
      | tr -d '\r'
  fi
}
login=$(app_q -F ' ' -c "select current_user, rolsuper, rolbypassrls from pg_roles
  where rolname = current_user" 2>&1) || problem "the application role $app cannot log in: $login"
read -r who super bypass <<<"${login:-x x x}"
[[ "$who" == "$app" && "$super" == "f" && "$bypass" == "f" ]] \
  || problem "the application role is not an unprivileged $app: $login"
if [[ -n "$probe" && "$sees_all" == "true" ]]; then
  tenant=$(qr -c "select tenant_uid from $probe where tenant_uid is not null
    group by 1 order by count(*) desc limit 1")
  expected=$(qr -c "select count(*) from $probe where tenant_uid = '$tenant'")
  # Without a tenant no pharmacy's row shows (a platform row, tenant_uid null, may: audit).
  closed=$(app_q -c "select count(*) from $probe where tenant_uid is not null" 2>&1) \
    || problem "$app cannot read $probe: $closed"
  [[ "$closed" == "0" ]] || problem "$app sees $closed pharmacy rows of $probe without a tenant: RLS is not failing closed"
  open=$(app_q -c "select set_config('pharmacy.tenant_uid', '$tenant', false)" \
    -c "select count(*) from $probe where tenant_uid is not null" 2>&1 | tail -1) || true
  [[ "$open" == "$expected" ]] || problem "$app sees $open rows of $probe as tenant $tenant, not $expected"
  log "application role: logs in unprivileged; $probe: $closed pharmacy rows without a tenant, $open of $expected as tenant $tenant"
else
  log "application role: logs in unprivileged (no tenant rows to probe RLS with)"
fi
step "application role checked"

total=$(( $(date +%s) - started ))
if (( problems > 0 )); then
  fail "$problems checks failed after $total s: do not use $RESTORE_DB"
fi
log "OK: $RESTORE_DB restored and checked in $total s ($total_tables tables, $total_rows rows)"
