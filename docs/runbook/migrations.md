# Migrations

Each module's schema is migrated by its own Flyway, in a fixed order (`ModuleMigrations.SCHEMAS`: `platform`,
`audit`, `tenancy`, `access`, … `insurance`). Each schema has its own `flyway_schema_history` table. Migrations
run as the **owner** role (`pharmacy_owner`). The API serves as the **application** role (`pharmacy_app`), which
neither owns the schemas nor bypasses row-level security.

## Modes

`pharmacy.migrations.mode` (`PHARMACY_MIGRATIONS_MODE`) sets the mode:

| Mode | What the process does | Owner credentials | Use |
|---|---|---|---|
| `migrate-and-run` (default) | Migrates at start, then serves | needed | A laptop, Railway |
| `migrate-only` | Migrates every schema and exits: code 0 when all are at the latest version, 1 on failure. No web server, no pool | needed | The one-off ECS task before a deploy |
| `run-only` | Never migrates. On start it reads each schema's history as the application role and refuses to start if a migration is pending or an applied one differs from this build | **not needed**: leave `PHARMACY_DB_OWNER_PASSWORD` unset | The ECS API service |

- **The old switch.** `PHARMACY_DB_MIGRATIONS_ENABLED=false` still means `run-only`.
- **A warning, not a refusal.** In production the API logs a warning when it runs `migrate-and-run`, because that
  instance holds the owner password and so can turn row-level security off. It also warns when a `run-only`
  service still holds the owner password. Railway still migrates at start, so neither stops the API yet.
- **Migrations newer than the build.** In `run-only`, migrations that the database has and this build does not
  are allowed. During a rolling deploy, the previous release's tasks keep starting against the newer schema.
  Migrations are additive (expand, migrate, contract), so the previous release keeps working on it.

## The ECS deploy sequence

The API service and the migrate task share one image. They differ only in their task definitions.

| Task definition | `PHARMACY_MIGRATIONS_MODE` | Secrets (Secrets Manager → env) | Plain environment |
|---|---|---|---|
| `pharmacy-migrate` (one-off task) | `migrate-only` | `PHARMACY_DB_OWNER_PASSWORD` | `PHARMACY_DB_URL`, `PHARMACY_DB_USER=pharmacy_app` (the name only: it receives the grants), `PHARMACY_DB_OWNER_USER=pharmacy_owner`, optional `PHARMACY_MIGRATIONS_LOCK_TIMEOUT` / `_STATEMENT_TIMEOUT`, `PHARMACY_LOG_FORMAT=ecs` |
| `pharmacy-api` (service, 2 or more tasks) | `run-only` | `PHARMACY_DB_PASSWORD`, `PHARMACY_ACCESS_ENCRYPTIONKEY`, `PHARMACY_KEYS_<ID>` (each key), `PHARMACY_FILES_SIGNING_SECRET` (local storage only), `PHARMACY_ROOT_PASSWORD` (first start only), provider and S3 secrets as used | `PHARMACY_DB_URL`, `PHARMACY_DB_USER`, `PHARMACY_SEALING_*`, `PHARMACY_ACCESS_WEBURL`, … |

- **The migrate task.** It holds no application password, no encryption key and no other secret.
- **The service.** It holds no owner secret. Give each task definition its own execution role. Only the migrate
  task's role may read the owner secret.

Each release:

1. **Build and push** the image, tagged with the git SHA. Register new revisions of both task definitions that
   point to it.
2. **Run the migrations** as a one-off Fargate task, in the API's private subnets and security group, so it can
   reach RDS:

   ```bash
   TASK=$(aws ecs run-task --cluster pharmacy --launch-type FARGATE \
     --task-definition pharmacy-migrate:<rev> \
     --network-configuration 'awsvpcConfiguration={subnets=[subnet-…],securityGroups=[sg-…],assignPublicIp=DISABLED}' \
     --query 'tasks[0].taskArn' --output text)
   aws ecs wait tasks-stopped --cluster pharmacy --tasks "$TASK"
   aws ecs describe-tasks --cluster pharmacy --tasks "$TASK" \
     --query 'tasks[0].containers[0].exitCode' --output text     # must print 0
   ```

3. **Stop on anything but 0.** Read the task's log in CloudWatch. It names the schema and the migration that
   failed. See "When a migration fails" below. The service keeps running the previous release on the previous
   schema.
4. **Update the service:**
   `aws ecs update-service --cluster pharmacy --service pharmacy-api --task-definition pharmacy-api:<rev>`.
   Each new task checks that the schema is current and starts; a task that finds a pending migration exits with
   the list, and ECS keeps the old tasks. Wait with `aws ecs wait services-stable`.

Two migrate tasks started together do not collide. Flyway takes a PostgreSQL advisory lock per schema, and the
second task waits for the first.

## Lock and statement timeouts

Every migration connection starts with these settings:

```sql
SET lock_timeout = '5s'        -- PHARMACY_MIGRATIONS_LOCK_TIMEOUT
SET statement_timeout = '5min' -- PHARMACY_MIGRATIONS_STATEMENT_TIMEOUT
```

- **Why the lock timeout.** An `ALTER TABLE` waits for the table's ACCESS EXCLUSIVE lock. Every later query on
  that table, including the live API's, queues behind it. With the lock timeout the migration gives up after
  5 seconds instead of stalling the pharmacies. PostgreSQL rolls the migration back (`canceling statement due to
  lock timeout`), and the task exits 1. Run it again; it usually succeeds once the long transaction ends.
  - **Find the blocker** if it keeps failing:
    `select pid, state, xact_start, query from pg_stat_activity where xact_start < now() - interval '1 minute'`.
- **Why the statement timeout.** It stops a runaway statement. A migration that really needs longer (a large
  backfill) sets it for itself at its start, for example `set local statement_timeout = '30min';`.
  - **Or, for one run**, set `PHARMACY_MIGRATIONS_STATEMENT_TIMEOUT` on the migrate task.

## When a migration fails: what is left, and how to resume

Flyway migrates one schema after another, and inside a schema one migration after another. **Each migration is
its own transaction.** PostgreSQL's DDL is transactional, so a failed migration leaves nothing behind.

Say the run fails in schema *S*, at migration *V<sub>n</sub>*. Then:

| | State |
|---|---|
| Schemas before *S* | Fully migrated (committed) |
| *S*, migrations before *V<sub>n</sub>* | Applied and recorded in `S.flyway_schema_history` |
| *S*, *V<sub>n</sub>* | Rolled back completely: no change, no history row |
| *S*, migrations after *V<sub>n</sub>* | Not run |
| Schemas after *S* | Untouched |

**To resume,** fix the cause (a lock, a timeout, bad data), then run the migrate-only task again. Flyway skips
what is applied and goes on from *V<sub>n</sub>*. Nothing needs repairing. The API (`run-only`) does not start on
the new release until every schema is current, and the previous release keeps serving meanwhile.

A failed migration whose SQL itself is wrong needs a new build. Fix it in the same file only if it never applied
anywhere. A migration that applied anywhere is never edited (its checksum would no longer match). Fix it forward
with a new migration instead.

### Non-transactional migrations (`CREATE INDEX CONCURRENTLY`)

`CREATE INDEX CONCURRENTLY` cannot run inside a transaction. Put it **alone** in its own migration file, which
Flyway then runs without a transaction. Keep `lock_timeout` in mind: it still waits for a brief lock at the start
and the end.

If such a migration fails, two things are left behind:
- a failed row in the history (`success = false`);
- usually an **invalid** index.

To resume, as the owner:

```sql
select version, script, success from <schema>.flyway_schema_history where not success;
select indexrelid::regclass from pg_index where not indisvalid;        -- the half-built index
drop index concurrently if exists <schema>.<index>;
delete from <schema>.flyway_schema_history where not success;          -- what `flyway repair` does
```

Then run the migrate-only task again.

## Writing migrations that are safe on a live database

`architecture-tests/MigrationSafetyTest` migrates an embedded PostgreSQL and checks every migration against the
database as it stood just before that migration ran:

- **Backfills reach every pharmacy.**
  - **The rule.** An `UPDATE` or `DELETE` on a table with forced row-level security must first run
    `alter table … no force row level security` and must put the force back afterwards. This covers plain SQL
    and `DO` blocks. Forced RLS applies to the owner too, so without the lift the change silently skips every
    pharmacy's rows.
  - **The alternative.** A `DO` block can instead loop over the pharmacies and set `pharmacy.tenant_uid` for each.
  - **Where the test reads it.** It takes the forced tables from `pg_class.relforcerowsecurity`, so tables forced
    in a `DO` loop count too.
- **No table rewrite.** `ADD COLUMN` on an existing table must not rewrite it. The rewrites are:
  - a volatile default (`gen_random_uuid()`, `clock_timestamp()`);
  - an identity or serial column;
  - a stored generated column.

  Add the column without a default, backfill it in batches, then set the default.
- **Indexes on big tables are `CONCURRENTLY`.**
  - **On the big tables** (`MigrationSafetyTest.BIG_TABLES`: sales, movements, journal, messages, audit …), a
    plain `CREATE INDEX` fails the test. Index a table created in the same migration freely.
  - **On other existing tables** it is reported in the test output, not failed.
  - **Already applied.** Three migrations that already ran are allowlisted with their reason:
    `audit/V4` (a partitioned table), `integrations/V4` and `notifications/V5` (both before production traffic).

## Railway

Railway keeps `migrate-and-run`: one service, migrating at start, as before. Its start-up log carries the
production-safety warning until it moves to the same pre-deploy step.
