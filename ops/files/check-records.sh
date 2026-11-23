#!/usr/bin/env bash
# Checks that every stored file the database knows of is in the S3 bucket, with its recorded size:
# the last step of the move from the local volume to S3 (docs/runbook/files-on-s3.md), and a check
# after a database restore or an S3 version restore (docs/runbook/backup-restore.md).
#
#   FILES_S3_URI=s3://pharmacy-files-prod/files/ ops/files/check-records.sh
#
# DATABASE: the libpq environment (PGHOST, PGPORT, PGDATABASE, PGUSER, PGPASSWORD or PGPASSFILE,
#   PGSSLMODE) as the backup role of ops/backup/backup-role.sql: files.files has forced
#   row-level security, so only a role with BYPASSRLS sees every pharmacy's files.
# S3: the aws CLI's own credentials (s3:ListBucket on the bucket is enough).
# FILES_S3_URI: the bucket and the key prefix (pharmacy.files.s3.prefix), such as s3://b/files/.
#
# Exit status 0 when every COMPLETE file is there with the same size; the missing and the
# different ones are listed (at most 50). One bucket listing, so it is quick for any number.
set -euo pipefail
umask 077

log() { printf '%s check-records: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2; }
fail() { log "FAILED: $*"; exit 1; }
for tool in psql aws awk sort; do
  command -v "$tool" >/dev/null 2>&1 || fail "$tool is not on the PATH"
done
uri="${FILES_S3_URI:-}"
[[ "$uri" == s3://* ]] || fail "FILES_S3_URI must be s3://bucket[/prefix/]"
rest="${uri#s3://}"
bucket="${rest%%/*}"
prefix=""
[[ "$rest" == */* ]] && prefix="${rest#*/}"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

sees_all=$(psql -X -q -A -t -v ON_ERROR_STOP=1 -c "select (rolsuper or rolbypassrls)::text
  from pg_roles where rolname = current_user" | tr -d '\r')
[[ "$sees_all" == "true" ]] || fail "this role has no BYPASSRLS: it would see no pharmacy's files"
psql -X -q -A -t -F ' ' -v ON_ERROR_STOP=1 -c "select storage_key, size_bytes from files.files
  where status = 'COMPLETE' and storage_key is not null order by storage_key" \
  | tr -d '\r' >"$work/records"
aws s3api list-objects-v2 --bucket "$bucket" --prefix "$prefix" \
  --query 'Contents[].[Key, Size]' --output text \
  | awk -v p="$prefix" -F '\t' '$1 != "None" {print substr($1, length(p) + 1), $2}' \
  | sort >"$work/objects"

awk 'NR == FNR {size[$1] = $2; next}
  !($1 in size) {print "missing  " $1; bad++; next}
  size[$1] != $2 {print "size     " $1 " (record " $2 " bytes, bucket " size[$1] ")"; bad++}
  END {exit bad > 0}' "$work/objects" "$work/records" >"$work/problems" && ok=true || ok=false

records=$(wc -l <"$work/records" | tr -d ' ')
objects=$(wc -l <"$work/objects" | tr -d ' ')
if [[ "$ok" == "true" ]]; then
  log "OK: all $records stored files are in s3://$bucket/$prefix with their sizes ($objects objects there)"
else
  head -50 "$work/problems" >&2
  fail "$(wc -l <"$work/problems" | tr -d ' ') of $records stored files are missing or differ"
fi
