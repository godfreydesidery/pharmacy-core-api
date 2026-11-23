# Backup and restore

What protects Pharmacy Core's data, how to get it back, and the proof that it works (drills). It
answers OPS-4 of the adversarial review (23 Nov 2026). Where the files live is in
[files-on-s3.md](files-on-s3.md).

## Targets

| | Target | How |
|---|---|---|
| **RPO** (data that may be lost) | **5 minutes** | RDS point-in-time recovery: the transaction log goes to S3 every 5 minutes. Files: S3 versioning, so no loss. |
| **RTO** (time until pharmacies sell again) | **1 hour** | About 10–30 min for an RDS restore to a new instance at today's size, 10 min for the checks, 5 min to point the API at it and roll the tasks, and the rest as margin. Measure it in every AWS drill and change the target if it does not hold. |
| Retention | 35 days to any minute; monthly archives for years | RDS automated backups (35 days, the PRD's promise), AWS Backup snapshots, and the encrypted logical archive in a separate bucket |

## The layers

| # | Layer | Protects against | Kept | Owner |
|---|---|---|---|---|
| 1 | **RDS automated backups + PITR** (`backup_retention_period = 35`) | A bad deploy, a wrong `UPDATE`, a lost instance | 35 days, to the second | Terraform |
| 2 | **AWS Backup**: a daily RDS snapshot in a vault with Vault Lock, copied to a second vault in **another AWS account** (same region) | Someone deleting the instance and its automated backups; a compromised production account | 35 daily snapshots, 12 monthly | Terraform |
| 3 | **S3 versioning** on the files bucket, noncurrent versions kept 35 days | A file deleted or overwritten by mistake or by a bug | 35 days | Terraform |
| 4 | **Logical archive**: `ops/backup/pg-dump-to-s3.sh`, a daily ECS scheduled task. It writes `pg_dump -Fc`, GPG-encrypted to a key whose private half is **not in AWS**, to a separate backup bucket with Object Lock. | Leaving AWS, losing the AWS account, needing a portable copy, long-term archive | daily 35 days; monthly 7 years (to confirm, see below) | This repo, Terraform for the task |

Layers 1–3 are AWS-native and the first choice for a restore. Layer 4 is the copy that does not
depend on AWS: it restores into any PostgreSQL 17+.

### What Terraform must set (handed to whoever writes it)

- **RDS:**
  - `backup_retention_period = 35`;
  - `backup_window = "23:00-23:30"` (02:00 in Dar es Salaam, the quietest hour);
  - `copy_tags_to_snapshot`, `deletion_protection = true`, `delete_automated_backups = false`;
  - a final snapshot on delete, and Multi-AZ;
  - PostgreSQL 17.
- **AWS Backup:**
  - a plan with a daily rule (35 days) and a monthly rule (12 months);
  - the vault under Vault Lock (governance mode, at least 7 days);
  - a copy action to a vault in a second account.
  - A copy to another region would take the data out of Africa (`af-south-1` is the only African
    region). That is the **owner's decision** and is off by default.
- **Files bucket:** see [files-on-s3.md](files-on-s3.md#what-aws-must-provide).
- **Backup bucket** (`pharmacy-backups-<env>`), apart from the files bucket:
  - Block Public Access, versioning, **Object Lock** (governance, 35 days default retention), SSE-KMS
    with its own key;
  - lifecycle: `daily/` expires after 35 days; `monthly/` moves to Glacier Deep Archive after 30
    days and expires after 7 years.
- **Backup task:** an ECS scheduled task (EventBridge Scheduler, `cron(30 23 * * ? *)`, after the
  RDS window).
  - The image is `postgres:17-alpine` with `apk add bash coreutils gnupg aws-cli`, plus
    `ops/backup/`. The `pg_dump` major version must be at least the server's.
  - Ephemeral storage of at least twice the dump size (Fargate encrypts it).
  - Environment, with secrets from Secrets Manager:

    | Variable | Value |
    |---|---|
    | `PGHOST` | the RDS endpoint |
    | `PGDATABASE` | `pharmacy` |
    | `PGUSER` | `pharmacy_backup` |
    | `PGPASSWORD` | secret |
    | `PGSSLMODE` | `verify-full`, with `PGSSLROOTCERT` the RDS CA bundle |
    | `BACKUP_S3_URI` | `s3://pharmacy-backups-prod/pg` |
    | `BACKUP_KMS_KEY_ID` | the backup bucket's key |
    | `BACKUP_GPG_RECIPIENT` | the backup key's fingerprint |
    | `BACKUP_GPG_PUBLIC_KEY` | the armoured public key, from SSM. It is public, not a secret. |

  - Task role:
    - `s3:PutObject` and `s3:GetObject` on `pharmacy-backups-<env>/pg/*` (head-object needs
      GetObject);
    - `kms:GenerateDataKey` on the backup key.
  - **Alarm:** the task's exit status. A failed run, or no run in 26 hours, pages the on-call.
- **Operators' restore role** (break-glass, MFA):
  - `rds:RestoreDBInstanceToPointInTime`, `rds:RestoreDBInstanceFromDBSnapshot`, `backup:StartRestoreJob`;
  - `s3:GetObject`/`ListBucket`/`GetObjectVersion`/`ListBucketVersions`/`PutObject` on the files bucket;
  - `s3:GetObject` on the backup bucket, and `kms:Decrypt`.

### The backup key

- GPG key pair "Pharmacy Core backup <env>": RSA 4096 or Curve25519, encryption only, created
  offline.
- The **private key and its passphrase** stay with the owner and one other trusted person, **never
  in AWS**: a password manager plus an offline copy (USB or paper) in a safe. Without it the
  archives cannot be read, by an attacker or by us.
- The public key goes to SSM.
- Test the private key in every drill (the restore decrypts with it).
- Rotate it by adding a new recipient. Old archives keep needing the old key until they expire.

### The database role for backups

`ops/backup/backup-role.sql` creates `pharmacy_backup` once per server: `LOGIN BYPASSRLS` plus
`pg_read_all_data`. It reads every row and writes nothing.

- **BYPASSRLS is necessary.** Every tenant table has FORCED row-level security. That binds the
  owner role too (Drill 1 found `pharmacy_owner` has no BYPASSRLS), so any other role dumps
  nothing.
- `pg-dump-to-s3.sh` refuses to run as a role that cannot see every row.
- On RDS, check that the master user may grant BYPASSRLS:
  `select rolbypassrls from pg_roles where rolname = current_user`. If it may not, run the backup as
  the master user.
- The application role `pharmacy_app` must **never** have BYPASSRLS.

### What the archive contains

`pg-dump-to-s3.sh` writes `<label>/<yyyy>/<mm>/<dd>/<db>-<utc>.dump.gpg`, its `.sha256` and a
`.manifest.json`. The manifest records:

- server and `pg_dump` versions;
- size and SHA-256;
- the number of TOC entries and tables;
- the Flyway version of every schema;
- how many tenant tables have forced RLS, and which platform tables have none by design.

The script checks the archive (`pg_restore --list`) before it encrypts it. It deletes the clear
dump at the end and checks the upload's size in S3. It never prints a password or a key.

## Restore procedures

**First decide what you are restoring to.** Always restore to a **new** instance or database, never
over the live one.

1. Find the last good moment T: the deploy, the bad statement, the time of the report.
2. Stop the damage. Set the API to read-only if the bug is still writing, or scale the ECS service
   to 0 if the data is being destroyed.
3. Tell the pharmacies (owner). Sales made between T and the restore are lost unless they are
   re-entered. The POS keeps unsent sales offline.

### A. RDS point-in-time recovery (the usual case)

```bash
aws rds restore-db-instance-to-point-in-time --region af-south-1 \
  --source-db-instance-identifier pharmacy-prod \
  --target-db-instance-identifier pharmacy-prod-pitr-$(date -u +%Y%m%d%H%M) \
  --restore-time 2026-11-23T10:15:00Z \        # or --use-latest-restorable-time
  --db-subnet-group-name <same> --vpc-security-group-ids <same> \
  --db-parameter-group-name <same> --multi-az --no-publicly-accessible \
  --deletion-protection --copy-tags-to-snapshot --backup-retention-period 35
aws rds wait db-instance-available --db-instance-identifier pharmacy-prod-pitr-...
```

- The restored instance does **not** inherit every setting: name the subnet group, security
  groups, parameter group, Multi-AZ and backup retention as above.
- It starts its own backup chain.

**Then:**

1. Run the checks on it, as the master user or `pharmacy_backup`:
   `PGHOST=<new endpoint> PGUSER=pharmacy_backup RESTORE_DB=pharmacy ops/backup/restore.sh --checks-only`.
2. Reconcile the files: run `ops/files/check-records.sh` against the new instance.
   - A file deleted after T is still referenced by the restored rows. Bring its object back (C).
   - A file uploaded after T is an orphan in S3. That is harmless.
3. **Re-apply erasures.** If a pharmacy was erased after T, erase it again. Find it from the
   `TENANT_ERASED` entries in the old instance's `audit.entries`, or in the erasure log. A restore
   must never bring an erased pharmacy back.
4. Point the API at it. Either:
   - rename the instances (old → `-old`, new → `pharmacy-prod`), so the endpoint and secrets do not
     change; or
   - update `PHARMACY_DB_URL` in the secret and force a new ECS deployment.

   Watch readiness: Flyway validates and finds nothing to migrate.
5. Keep the old instance, stopped, until the owner closes the incident. Then take a final snapshot
   and delete it.

### B. From a snapshot (AWS Backup or manual)

```bash
aws rds describe-db-snapshots --db-instance-identifier pharmacy-prod --snapshot-type automated
aws backup list-recovery-points-by-backup-vault --backup-vault-name pharmacy-prod
aws rds restore-db-instance-from-db-snapshot --region af-south-1 \
  --db-instance-identifier pharmacy-prod-snap-... --db-snapshot-identifier <arn or id> \
  --db-subnet-group-name <same> --vpc-security-group-ids <same> --db-parameter-group-name <same>
# or: aws backup start-restore-job --recovery-point-arn <arn> --iam-role-arn <restore role> --metadata ...
```

The same "then" steps as A follow. A snapshot from the second account is first shared with (or
copied back to) the production account.

### C. A file, or many: S3 object versions

The bucket is versioned. A delete leaves a **delete marker**, and an overwrite leaves the old
version as noncurrent, for 35 days.

```bash
B=pharmacy-files-prod; K=files/tenants/<tenant>/2026/11/<file>
aws s3api list-object-versions --bucket $B --prefix "$K" \
  --query '[Versions[].[Key,VersionId,LastModified,IsLatest], DeleteMarkers[].[Key,VersionId,LastModified,IsLatest]]' \
  --output text
# deleted: remove the delete marker, and the previous version is current again
aws s3api delete-object --bucket $B --key "$K" --version-id <delete marker's VersionId>
# overwritten: copy the good version over the current one
aws s3api copy-object --bucket $B --key "$K" --copy-source "$B/$K?versionId=<good VersionId>" \
  --server-side-encryption aws:kms --ssekms-key-id <key>
```

For many files, take the `missing` keys that `ops/files/check-records.sh` lists, and for each one
remove its latest delete marker:

```bash
ops/files/check-records.sh 2>&1 | awk '$1 == "missing" {print $2}' | while read -r key; do
  marker=$(aws s3api list-object-versions --bucket $B --prefix "files/$key" \
    --query "DeleteMarkers[?IsLatest].VersionId" --output text)
  [ -n "$marker" ] && [ "$marker" != None ] && aws s3api delete-object --bucket $B --key "files/$key" --version-id "$marker"
done
```

Run `check-records.sh` again until it says `OK`. An erased pharmacy's files are not restored: their
records are gone, so they are never listed.

### D. From the logical archive (leaving AWS, or layers 1–3 lost)

On any machine with PostgreSQL 17+ client tools, `gpg` holding the backup private key, and the aws
CLI (or the files copied by hand):

```bash
export PGHOST=<new server> PGUSER=<superuser or RDS master> PGPASSWORD=...   # admin of the target
RESTORE_DB=pharmacy RESTORE_APP_PASSWORD=... \
  ops/backup/restore.sh s3://pharmacy-backups-prod/pg/daily/2026/11/23/pharmacy-20261123T233000Z.dump.gpg
```

It runs these steps:

1. It downloads the archive and checks its SHA-256.
2. It decrypts it and checks that it is a pg_dump archive.
3. It creates the roles it lacks, with no passwords. Set them before the API connects.
4. It creates the **new** database, owned by `pharmacy_owner`. It refuses one that exists.
5. It restores in two passes, as the owner, so ownership is as in production:
   - first everything but the row-level security;
   - then RLS and the policies.

   Forced RLS binds the owner too, so foreign keys would otherwise be refused, as Drill 1 found.
6. It runs the checks, and exits 1 if any fails:
   1. **Flyway**: no failed migration in any schema, and versions equal to the manifest's.
   2. **Rows**: an exact count of every table, by schema. With `RESTORE_COMPARE_DSN`, it must
      equal the source's.
   3. **RLS**: every table with `tenant_uid` has RLS enabled and forced, except the platform
      tables that have none by design (as in the manifest and `RowLevelSecurityTest`). The forced
      count equals the manifest's, and the policies are there.
   4. **The application role**:
      - it logs in, and is neither superuser nor BYPASSRLS;
      - without a tenant it sees **no pharmacy's row** (fail closed; platform rows such as
        root's audit entries may show);
      - as a tenant it sees exactly that tenant's rows.

Then point the API at it. On first start Flyway validates and finds nothing to migrate. After that,
copy the files (the files bucket, or its own backups) and run `check-records.sh`.

### Archive retention and erasure

- The monthly archive is kept 7 years. This is a **proposal for the owner and the accountant**:
  tax records must be kept for years, and controlled-drug registers have their own rules.
- An erased pharmacy's rows stay in older archives and snapshots until those expire. The privacy
  notice must say so: up to 35 days for layers 1–3, and the archive period for layer 4.
- Every restore of a backup older than an erasure re-applies the erasure (step A.3).

## Who does what

| Role | Does |
|---|---|
| **Owner** | Decides that a restore happens and to which moment. Tells the pharmacies. Holds the backup private key with one other trusted person. Signs off each drill. |
| **On-call engineer** | Runs the procedure above and the checks, and records the timings. Never points production at a database whose checks failed. |
| **Second person** (another engineer, or the owner) | Reads the check output before production is switched (four eyes). |
| **Infrastructure** (Terraform) | RDS backup settings, AWS Backup plan and vaults, the buckets with versioning, lifecycle and Object Lock, IAM and KMS, the backup task and its alarm. |

## Quarterly drill checklist

Every quarter, and after any change to the schema tooling, the RLS design or the backup scripts.
Record each drill below as "Drill N".

- [ ] **PITR**: restore production to a new instance at a time 1–24 hours ago (A). Time it.
- [ ] Run `restore.sh --checks-only` on it. Every check passes.
- [ ] Start one API task against it (a separate service with no traffic). Readiness turns UP and
      Flyway reports nothing to migrate.
- [ ] Run `ops/files/check-records.sh` against it. Note how many objects were missing and bring
      them back from versions (C).
- [ ] **S3 version**: delete a test object, then restore it (C).
- [ ] **Snapshot**: restore the newest AWS Backup recovery point from the second account (B).
- [ ] **Archive**: restore last night's `pg-dump-to-s3` archive with `restore.sh` on a machine
      outside AWS (D). Decrypt with the offline private key. Every check passes.
- [ ] Check the backup task's last 90 runs: none missed, the alarm tested.
- [ ] Compare the measured RTO with 1 hour and the PITR latest restorable time with 5 minutes.
- [ ] Delete the drill instances and databases. Write "Drill N" below, then the owner signs it off.

## Drill 1 — 23 Nov 2026, local, logical backup and restore

**Scope.** Layer 4 (the logical archive) for real, end to end, on a laptop: Windows 11, PostgreSQL
17.6 servers (the embedded binaries), PostgreSQL 18.6 client tools, GnuPG 2.4.8. There is no AWS
account yet, so layers 1–3 wait for the first AWS drill. The S3 path of the scripts was also run
against an S3 API (S3Mock in Docker).

**Source.** A copy of the QA database (data directory `qa-b5`, 82 MB). The original was not touched.
The copy holds:

- database `pharmacy`, 25 MB;
- 23 pharmacies;
- 17 module schemas;
- 129 tables (125 without partitions);
- 9,751 rows;
- roles `pharmacy_owner` and `pharmacy_app` as in production.

### Steps and timings

| Step | Time | Result |
|---|---|---|
| Copy the data directory to a scratch folder | 3 s | 82 MB |
| Start the copy (port 55731) | 0.5 s | |
| `backup-role.sql` | <1 s | `pharmacy_backup`: BYPASSRLS, `pg_read_all_data`, at most 3 connections |
| `pg-dump-to-s3.sh` as `pharmacy_owner` | <1 s | **Refused** at once: the role cannot see every row. This is the intended behaviour. |
| `pg-dump-to-s3.sh` as `pharmacy_backup` (upload skipped) | **2.4 s** | Dump 1,123,077 bytes, 1,302 TOC entries, encrypted to 1,123,695 bytes. Manifest with all 17 Flyway versions. The clear dump was deleted. |
| New empty server: `initdb` + start (port 55732) | 10.9 s + 0.8 s | |
| `restore.sh` (checksum, decrypt, roles, database, two-pass restore, analyze, all checks) | **9.4 s** | `OK`: about 3 s to restore, 6 s for the checks |
| The real API (`pharmacy-core-*-exec.jar`) started on the restored database, migrations on | 21.5 s | Flyway validated all 17 schemas: "No migration necessary". Hibernate `validate` passed. Readiness `UP`. `POST /auth/sign-in` answered (401 for an unknown user). |

From the archive file to a checked database took about **10 seconds**. With a new server it took
under 25 seconds.

### Check results

- **Flyway:** 17 schemas, 0 failed migrations. The versions equal the manifest's (access 18,
  audit 6, catalogue 12, files 3, finance 6, fx 2, insurance 3, integrations 5, inventory 9,
  notifications 5, partners 5, platform 7, purchasing 5, receivables 2, reporting 2, sales 12,
  tenancy 14).
- **Rows:** the count of each of the **125 tables equals the source's** (9,751 rows). By schema:
  access 3,039, audit 3,682, catalogue 363, files 6, finance 574, fx 8, insurance 17,
  integrations 33, inventory 478, notifications 277, partners 89, platform 539, purchasing 86,
  receivables 21, reporting 163, sales 264, tenancy 112.
- **RLS:**
  - 90 of 93 tenant tables have RLS enabled and forced, with 101 policies, identical to the source.
  - The 3 without RLS are the platform tables that have none by design: `tenancy.subscriptions`,
    `tenancy.subscription_invoices`, `access.session_pharmacies`.
- **Application role:**
  - it logs in and is neither superuser nor BYPASSRLS;
  - `audit.entries` shows **0 pharmacy rows** without a tenant (only root's platform entries, as
    the policy intends);
  - it shows 1,030 of 1,030 rows as that tenant.

### The S3 path (S3Mock, same day)

- `pg-dump-to-s3.sh` uploaded the archive, its `.sha256` and the manifest under
  `pg/monthly/2026/11/23/` in 8 s. head-object confirmed the size and `AES256`.
- `restore.sh s3://…` downloaded, checked, decrypted, restored and checked in 18 s. It compared the
  Flyway versions and the RLS list with the downloaded manifest.
- The files copy (`--pharmacy.files.copy-local-to-s3=true`, through the built jar, the restored
  database and S3Mock):
  - Pass 1 copied 2 files (one of 20 MB as a multipart upload) and left the `.part` file out, in
    2 s, exit 0. The recorded `sha256` equals the local file's.
  - Pass 2 (`copy-verify=full`) copied nothing and checked both, exit 0.
- `ops/files/check-records.sh` gave the right answer in all four cases:
  - all present: `OK`;
  - a wrong size: listed, exit 1;
  - a missing object: listed, exit 1;
  - run as the application role: refused.

### What the drill found, fixed in the scripts

1. **Foreign keys are refused under forced RLS during a restore as the owner.** `ALTER TABLE …
   ADD FOREIGN KEY` failed with "query would be affected by row-level security policy for table
   journal_lines". Parallel jobs had enabled RLS before the key's check. `restore.sh` now restores
   in two passes, with RLS and the policies last. That works as the owner role and needs no
   BYPASSRLS for the restore itself.
2. **The owner role cannot back up.** `pharmacy_owner` has no BYPASSRLS, and forced RLS hides every
   tenant row from it. Hence `pharmacy_backup` and the refusal.
3. **Not every tenant table has RLS, by design.** The RLS check holds the restore to the source's
   list (recorded in the manifest) instead of a blanket rule.
4. **The fail-closed probe must ignore platform rows.** Audit's policy shows root's entries
   (`tenant_uid` null) when no tenant is set. The probe counts only rows that belong to a pharmacy.
5. **On Windows:**
   - psql ends lines with CRLF, so the scripts now strip CR;
   - gpg-agent refuses a home directory whose socket path is longer than about 100 characters, so
     use a short `GNUPGHOME`. The drill used `subst W:`.

### Not covered yet

RDS PITR, snapshot restore, the cross-account copy, S3 version restore on real S3, the measured
RTO on AWS, and the backup task's schedule and alarm. These are for the first AWS drill (Drill 2),
once Terraform has built `af-south-1`.
