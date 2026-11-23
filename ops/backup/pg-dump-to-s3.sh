#!/usr/bin/env bash
# Portable logical backup of the Pharmacy Core database.
#
#   pg_dump -Fc  ->  check the archive  ->  encrypt to the backup public key (GPG)  ->  S3
#
# For leaving AWS and for the long-term archive. It is the second line of defence: RDS automated
# backups (point-in-time recovery) and AWS Backup snapshots come first. See
# docs/runbook/backup-restore.md.
#
# Runs anywhere bash, the PostgreSQL client tools (pg_dump at least the server's major version),
# gpg, sha256sum and the aws CLI are: an ECS scheduled task, a laptop, a CI job.
#
# CONNECTION: the libpq environment, nothing on the command line:
#   PGHOST, PGPORT, PGDATABASE, PGUSER, and PGPASSWORD or PGPASSFILE, PGSSLMODE (use verify-full
#   with the RDS CA bundle in PGSSLROOTCERT on AWS).
#   The role must read every row: every tenant table has FORCED row-level security, so a role
#   without BYPASSRLS dumps nothing useful (pg_dump stops with "query would be affected by
#   row-level security policy"). Use the read-only backup role of ops/backup/backup-role.sql.
#
# REQUIRED
#   BACKUP_S3_URI              s3://bucket/prefix the backup goes under (not needed with
#                              BACKUP_SKIP_UPLOAD=true)
#   BACKUP_GPG_RECIPIENT       the key id, fingerprint or email to encrypt to; the public key is
#                              imported from BACKUP_GPG_PUBLIC_KEY_FILE or BACKUP_GPG_PUBLIC_KEY
#                              (ASCII-armoured; for a secret store), else taken from the keyring.
#                              The private key never goes near this script.
# OPTIONAL
#   BACKUP_LABEL               daily (the default) or monthly (the default on the 1st, UTC); the
#                              S3 lifecycle rules keep each label for its own time
#   BACKUP_KMS_KEY_ID          SSE-KMS key of the upload (SSE-S3 without it)
#   BACKUP_WORK_DIR            where the dump is made (default: a new temp directory, removed at
#                              the end; needs about twice the dump's size)
#   BACKUP_SKIP_UPLOAD=true    keep the result in BACKUP_OUTPUT_DIR instead of uploading (drills)
#   BACKUP_OUTPUT_DIR          with BACKUP_SKIP_UPLOAD
#   BACKUP_COMPRESS            pg_dump compression level (default 6)
#
# OUTPUT (under <BACKUP_S3_URI>/<label>/<yyyy>/<mm>/<dd>/):
#   <db>-<utc time>.dump.gpg                    the encrypted custom-format archive
#   <db>-<utc time>.dump.gpg.sha256             its SHA-256 (sha256sum format)
#   <db>-<utc time>.manifest.json               what is in it: versions, sizes, Flyway versions
# Exit status 0 only when the archive was made, checked, encrypted, uploaded and found in S3.
#
# It never prints a password or a key: there is no `set -x`, the password stays in the libpq
# environment, and only names, sizes and hashes are logged.
set -euo pipefail
umask 077

log() { printf '%s pg-dump-to-s3: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2; }
fail() { log "FAILED: $*"; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || fail "$1 is not on the PATH"; }

need pg_dump
need pg_restore
need psql
need gpg
need sha256sum
skip_upload="${BACKUP_SKIP_UPLOAD:-false}"
if [[ "$skip_upload" != "true" ]]; then
  need aws
  [[ -n "${BACKUP_S3_URI:-}" ]] || fail "BACKUP_S3_URI is not set"
  [[ "${BACKUP_S3_URI}" == s3://* ]] || fail "BACKUP_S3_URI must start with s3://"
else
  [[ -n "${BACKUP_OUTPUT_DIR:-}" ]] || fail "BACKUP_OUTPUT_DIR is needed with BACKUP_SKIP_UPLOAD"
  mkdir -p "$BACKUP_OUTPUT_DIR"
fi
[[ -n "${BACKUP_GPG_RECIPIENT:-}" ]] || fail "BACKUP_GPG_RECIPIENT is not set"
[[ -n "${PGDATABASE:-}" ]] || fail "PGDATABASE is not set"

started=$(date +%s)
stamp=$(date -u +%Y%m%dT%H%M%SZ)
label="${BACKUP_LABEL:-}"
if [[ -z "$label" ]]; then
  [[ "$(date -u +%d)" == "01" ]] && label=monthly || label=daily
fi
name="${PGDATABASE}-${stamp}"

if [[ -n "${BACKUP_WORK_DIR:-}" ]]; then
  work="${BACKUP_WORK_DIR%/}/$name.work"
  mkdir -p "$work"
else
  work=$(mktemp -d)
fi
# The clear dump never outlives the run.
trap 'rm -rf "$work"' EXIT

# --- the role must see every row ------------------------------------------------------------
# psql, unaligned; carriage returns dropped (psql on Windows ends lines with CRLF).
psql_q() { psql -X -q -A -t -v ON_ERROR_STOP=1 "$@" | tr -d '\r'; }
role_line=$(psql_q -c "select current_user || '|' || (rolsuper or rolbypassrls)::text || '|' ||
  current_setting('server_version_num') || '|' || current_setting('server_version')
  from pg_roles where rolname = current_user") || fail "cannot connect to ${PGHOST:-local}/${PGDATABASE}"
IFS='|' read -r who sees_all server_num server_version <<<"$role_line"
[[ "$sees_all" == "true" ]] || fail "role $who has no BYPASSRLS: it would not see the rows of the tenant tables (forced RLS)"
dump_major=$(pg_dump --version | sed -E 's/.* ([0-9]+)(\.[0-9]+)?.*/\1/')
(( dump_major * 10000 >= server_num - server_num % 10000 )) || fail "pg_dump $dump_major is older than the server ($server_version)"
log "dumping ${PGDATABASE} on ${PGHOST:-local} (PostgreSQL $server_version) as $who, label $label"

# What the manifest records, read before the dump (a live database moves on; the dump itself is
# one consistent snapshot).
flyway=$(psql_q -c "select coalesce(string_agg(format('\"%s\":\"%s\"', s, v), ',' order by s), '')
  from (select h.table_schema as s,
    (xpath('/row/v/text()', query_to_xml(format(
      'select version as v from %I.flyway_schema_history where success and version is not null
       order by installed_rank desc limit 1', h.table_schema),
      false, true, '')))[1]::text as v
    from information_schema.tables h where h.table_name = 'flyway_schema_history') x")
tables=$(psql_q -c "select count(*) from information_schema.tables
  where table_type = 'BASE TABLE' and table_schema not in ('pg_catalog', 'information_schema')")
# Tables with a tenant_uid column: how many have RLS enabled and forced, and which have none (the
# platform-scope ones, by design); restore.sh holds the restored database to the same.
rls=$(psql_q -F '|' -c "select count(*) filter (where c.relrowsecurity and c.relforcerowsecurity),
    coalesce(string_agg(n.nspname || '.' || c.relname, ',' order by 1)
      filter (where not (c.relrowsecurity and c.relforcerowsecurity)), '')
  from pg_class c join pg_namespace n on n.oid = c.relnamespace
  where c.relkind in ('r', 'p') and n.nspname not in ('pg_catalog', 'information_schema')
    and exists (select 1 from pg_attribute a where a.attrelid = c.oid
      and a.attname = 'tenant_uid' and not a.attisdropped and a.attnum > 0)")
IFS='|' read -r rls_forced rls_none <<<"$rls"

# --- dump and check -------------------------------------------------------------------------
dump="$work/$name.dump"
t0=$(date +%s)
pg_dump --format=custom --compress="${BACKUP_COMPRESS:-6}" --no-password --file="$dump"
t1=$(date +%s)
toc_entries=$(pg_restore --list "$dump" | grep -cE '^[0-9]+;' || true)
(( toc_entries > 0 )) || fail "the archive lists no objects"
log "dump: $(stat -c %s "$dump" 2>/dev/null || wc -c <"$dump") bytes, $toc_entries entries, $((t1 - t0)) s"

# --- encrypt --------------------------------------------------------------------------------
export GNUPGHOME="$work/gnupg"
mkdir -p "$GNUPGHOME"
chmod 700 "$GNUPGHOME"
if [[ -n "${BACKUP_GPG_PUBLIC_KEY_FILE:-}" ]]; then
  gpg --batch --quiet --import "$BACKUP_GPG_PUBLIC_KEY_FILE" 2>/dev/null || fail "cannot import BACKUP_GPG_PUBLIC_KEY_FILE"
elif [[ -n "${BACKUP_GPG_PUBLIC_KEY:-}" ]]; then
  printf '%s\n' "$BACKUP_GPG_PUBLIC_KEY" | gpg --batch --quiet --import 2>/dev/null || fail "cannot import BACKUP_GPG_PUBLIC_KEY"
else
  unset GNUPGHOME # the caller's keyring
fi
encrypted="$work/$name.dump.gpg"
gpg --batch --yes --quiet --trust-model always --compress-algo none \
  --recipient "$BACKUP_GPG_RECIPIENT" --output "$encrypted" --encrypt "$dump" || fail "gpg could not encrypt"
rm -f "$dump"
size=$(stat -c %s "$encrypted" 2>/dev/null || wc -c <"$encrypted" | tr -d ' ')
sha=$(sha256sum "$encrypted" | cut -d' ' -f1)
printf '%s  %s\n' "$sha" "$name.dump.gpg" >"$encrypted.sha256"
t2=$(date +%s)
log "encrypted: $size bytes, sha256 $sha, $((t2 - t1)) s"

cat >"$work/$name.manifest.json" <<JSON
{
  "file": "$name.dump.gpg",
  "createdAt": "$stamp",
  "label": "$label",
  "database": "$PGDATABASE",
  "host": "${PGHOST:-local}",
  "serverVersion": "$server_version",
  "pgDump": "$(pg_dump --version)",
  "format": "custom, compress ${BACKUP_COMPRESS:-6}, then gpg to ${BACKUP_GPG_RECIPIENT}",
  "sizeBytes": $size,
  "sha256": "$sha",
  "tocEntries": $toc_entries,
  "tables": $tables,
  "rlsForcedTables": $rls_forced,
  "tenantTablesWithoutRls": "$rls_none",
  "flyway": {$flyway}
}
JSON

# --- upload and check -----------------------------------------------------------------------
if [[ "$skip_upload" == "true" ]]; then
  cp "$encrypted" "$encrypted.sha256" "$work/$name.manifest.json" "$BACKUP_OUTPUT_DIR/"
  log "kept in $BACKUP_OUTPUT_DIR (upload skipped), $(( $(date +%s) - started )) s in all"
  exit 0
fi
dest="${BACKUP_S3_URI%/}/$label/${stamp:0:4}/${stamp:4:2}/${stamp:6:2}"
if [[ -n "${BACKUP_KMS_KEY_ID:-}" ]]; then
  sse=(--sse aws:kms --sse-kms-key-id "$BACKUP_KMS_KEY_ID")
else
  sse=(--sse AES256)
fi
for file in "$encrypted" "$encrypted.sha256" "$work/$name.manifest.json"; do
  aws s3 cp "$file" "$dest/$(basename "$file")" "${sse[@]}" --only-show-errors \
    || fail "upload of $(basename "$file") failed"
done
bucket_and_key="${dest#s3://}/$name.dump.gpg"
remote_size=$(aws s3api head-object --bucket "${bucket_and_key%%/*}" --key "${bucket_and_key#*/}" \
  --query ContentLength --output text) || fail "the upload is not in S3"
[[ "$remote_size" == "$size" ]] || fail "S3 has $remote_size bytes, the archive $size"
log "uploaded to $dest/$name.dump.gpg ($size bytes), $(( $(date +%s) - started )) s in all"
