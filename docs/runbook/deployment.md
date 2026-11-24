# Deployment: running several instances

> This is the **"Running several instances"** section of `pharmacy-core-docs/runbook/deployment.md`
> (Batch 6, adversarial review 23 Nov 2026: OPS-7, OPS-8, INT-12, AUTH-14 and the owner's answer 7).
> It replaces the "Scaling → API" bullet there ("several instances are **not yet safe**") and the
> note "Metrics are not public; they will be scraped on a private port when monitoring is added".

## Running several instances

The API runs as several instances at once (for example two or more ECS Fargate tasks behind an
ALB). Nothing a request depends on is kept in one instance's memory: every instance reads and
writes the same PostgreSQL, so any instance can serve any request, and no sticky sessions are
needed.

### Shared through PostgreSQL

| What | How |
|---|---|
| Sessions, account locks | Rows in `access` (as before) |
| Scheduled jobs | Each job takes a PostgreSQL advisory lock named after it (`SingleRunner`, `pg_try_advisory_lock`); an instance that finds it taken skips the round. The lock goes with its connection, so a crashed task never leaves it held |
| Queue-like work | Rows are claimed by one worker: exports `FOR UPDATE SKIP LOCKED` with heartbeats; webhook deliveries `FOR UPDATE SKIP LOCKED` with a lease; notification messages by a conditional update one caller wins (with a lease); the SMS cap under a transaction advisory lock |
| Job status | `platform.job_runs`: job, instance, start, end, outcome (OK, FAILED, SKIPPED), message, the pharmacies a run failed for. Root reads it at `GET /api/v1/platform/jobs` (and `/jobs/{job}/runs`). 30 days are kept (`PHARMACY_JOB_RUNS_DAYS`) |
| Sign-in limits | Failures per client IP (20 in 15 min, then 15 min locked) and per unknown email (5 in 15 min), and the sign-in requests per IP (20 a minute): a sliding window per key in `platform.rate_windows`, updated by one atomic upsert per hit. Keys are stored as SHA-256 hashes (no email or IP address). Expired rows go with the nightly retention |
| Root activity | Every request of a root administrator is one audit entry (`ROOT_ACTED_IN_TENANT` in the pharmacy, `ROOT_REQUEST` on the platform), with its status and outcome; no counting needed (Batch 10, FRD-19) |
| Live notifications (SSE) | A new in-app message is signalled with `NOTIFY pharmacy_inapp` (payload: instance, pharmacy and user ids, no personal data). Each instance holds **one dedicated listener connection** (outside the pool, `application_name = pharmacy-signals`); the instance that holds the user's stream reads the message and the unread count from the database and pushes them. After the listener reconnects (database restart, failover), every open stream gets its current state again |
| Undelivered internal events | Delivered again by the `event-resubmission` job (every 5 min, older than 5 min, one instance). Republishing at start-up is off (`PHARMACY_EVENTS_REPUBLISH_ON_START=false`), because a starting instance would deliver again the events the others are still delivering |
| Files | `PHARMACY_FILES_STORAGE=s3` (one bucket for all instances). Local storage is one volume and does not work with several instances |

### Per instance, on purpose

| What | Why it stays per instance |
|---|---|
| The SSE connections themselves | A stream is a TCP connection to one task; the fan-out above reaches it from any instance |
| Fair share per pharmacy (`PHARMACY_TENANT_CONCURRENCY`, 10 requests at once) | It shares out **this instance's** database pool; with N instances a pharmacy may have N × 10 in progress, which N pools can serve |
| Argon2 hashing slots | They protect this instance's CPU and memory (19 MiB per hash) |
| Provider circuit breaker (`ProviderHealth`) | It only orders the providers to try; a tripped provider is still tried. Each instance learns after 3 failures of its own. The provider's last success and error are on its row, shared |
| "Worked in this pharmacy" write throttle | A pure cache that saves round trips: the one SIGN_IN audit entry per session and pharmacy is decided in the database, and `last_active_at` is a conditional update (written only when older than 5 minutes), so N instances still write it once per period |
| Short caches | A pharmacy's 2FA policy (30 s), time zone, the SMTP sender built from a provider row |
| The edge's access cache (`PHARMACY_EDGE_ACCESS_CACHE_SECONDS`, 30 s) | A session's pharmacy (status), membership (role permissions, branches) and branch (active), so most requests run one database transaction before their handler instead of four. The session itself is checked on every request. Sign-out, a member changed or deactivated, a role or branch changed, and a pharmacy suspended, closed, reopened or erased are evicted on every instance at once: the instance evicts, then sends `NOTIFY pharmacy_edge_access` (payload `t:<tenant>`, `u:<user>` or `*`). After a listener reconnect the cache is emptied. Anything else, such as the nightly subscription run suspending an unpaid pharmacy, is seen within 30 s |
| Requests in progress (`PHARMACY_MAX_IN_FLIGHT`, 100) | It protects **this instance's** pool and threads. One over the limit waits `PHARMACY_MAX_IN_FLIGHT_WAIT_MS` (250 ms), then gets 503 `server_busy` with `Retry-After: 2`. The live stream and the health probes are not counted |
| The notification senders (`PHARMACY_NOTIFY_THREADS` 8, `PHARMACY_NOTIFY_QUEUE` 2,000) | Deliver messages after commit. With a full queue a dispatch is dropped (logged `NOTIFY_REJECTED`, counted `pharmacy.notifications.senders.rejected`); its messages stay QUEUED and `notification-retries` sends them within a minute |
| The async reactions' pool (`PHARMACY_ASYNC_THREADS` 8, `PHARMACY_ASYNC_QUEUE` 10,000) | The ledger postings, read models, webhooks and notices after commit. With a full queue a reaction is refused (logged `ASYNC_REJECTED`) and its event is delivered again by `event-resubmission` |
| Stream heartbeats (25 s) | Each instance pings its own streams |
| The export worker's poll (5 s) | Every instance polls; the claim makes each export one instance's |

### Scheduled jobs

| Job (`platform.job_runs.job`) | When | Runs on |
|---|---|---|
| `retention` | 01:40 UTC | one instance (advisory lock) |
| `subscription-lifecycle` | 02:15 | one instance |
| `audit-partitions` | start and 02:15 UTC | one instance |
| `fx-refresh` | 06:00 UTC | one instance |
| `notification-digests` | every 15 min | one instance |
| `sensitive-digest` | every 15 min (sends at 07:00 local, the day before; dedup per day) | one instance |
| `notification-retries` | every minute | one instance; each message claimed once |
| `webhook-retries` | every minute | one instance; each delivery claimed once |
| `event-resubmission` | every 5 min | one instance |
| `expiry-provision` | hourly at :10; measures a pharmacy on days 1–7 of its local month, for the month before (STD-3) | one instance; one run per pharmacy and month |
| `vat-apportionment` | hourly at :20; same days, plus the year-end true-up (STD-2) | one instance; one run per pharmacy, month and kind |
| `data-export` | each export built | the instance that claimed it |
| stream heartbeats | every 25 s | every instance (its own streams; not recorded) |

A failed run (an exception, or a pharmacy the run failed for) is recorded FAILED, counted, and
logged at ERROR with the stable prefix **`JOB_FAILED`**:
`JOB_FAILED job=<job> instance=<instance> took=<ms>ms failures=<n> message=<...> failed for: <pharmacy> (<error>) ...`

### What AWS must provide

**Ports and the load balancer**

- Container port **8080**: the API, the ALB target group.
- Container port **8081**: actuator (health and Prometheus metrics), set with
  `PHARMACY_MANAGEMENT_PORT=8081`. Do not add it to the ALB listener; allow it in the task's
  security group only from inside the VPC (the metrics collector).
- ALB target group health check: `GET /readyz` on the traffic port (8080), healthy on 200. (On the
  management port the same check is `/actuator/health/readiness`.)
- ECS container health check (in the task definition, not the image's `HEALTHCHECK`):
  `CMD-SHELL wget -qO- http://localhost:8081/actuator/health/liveness || exit 1`, start period
  60–120 s (migrations run before readiness when they run at start).
- ALB idle timeout at least 60 s (the default) so SSE streams with a 25 s heartbeat stay open;
  deregistration delay of 30–60 s (the API shuts down gracefully).
- No sticky sessions.

**Environment**

| Variable | Value on ECS | Notes |
|---|---|---|
| `PHARMACY_MANAGEMENT_PORT` | `8081` | Unset: actuator shares 8080 and serves only health (local, Railway) |
| `PHARMACY_LOG_FORMAT` | `json` | One JSON object per line on stdout (Spring Boot structured logging, Elastic Common Schema: `@timestamp`, `log.level`, `log.logger`, `message`, `correlationId`, `error.*`). `ecs`, `logstash`, `gelf` also work; unset is plain text |
| `PHARMACY_FILES_STORAGE` | `s3` | With `PHARMACY_FILES_S3_BUCKET` and `PHARMACY_FILES_S3_REGION=af-south-1`; the task role grants the bucket |
| `PHARMACY_DB_POOL_SIZE` | e.g. `20` | Per instance. Keep N × (pool + 1 listener) + the migrator below RDS `max_connections` |
| `PHARMACY_INSTANCE_ID` | optional | Defaults to the host name (the task's) and a random suffix; shown in job runs |
| `SENTRY_DSN` | optional | The error tracker (Sentry). Off when unset; no personal data is sent (`send-default-pii=false`, no request bodies). `SENTRY_ENVIRONMENT` tells servers apart |
| `PHARMACY_JOB_RUNS_DAYS` | `30` | How long job runs are kept |
| `PHARMACY_EVENTS_REPUBLISH_ON_START` | `false` | Keep false with several instances |
| `PHARMACY_DB_STATEMENT_TIMEOUT` | `30s` | `statement_timeout` of every pooled connection. Longer statements are cancelled and the request gets 503 `database_busy`. A transaction declared with `@Transactional(timeout = ...)` raises it for itself (`SET LOCAL`). So does a scheduled job (`PHARMACY_DB_JOB_STATEMENT_TIMEOUT`, `5m`) |
| `PHARMACY_DB_LOCK_TIMEOUT` | `5s` | `lock_timeout`. A longer lock wait fails, and the request gets 409 `busy_try_again` |
| `PHARMACY_DB_IDLE_IN_TRANSACTION_TIMEOUT` | `60s` | `idle_in_transaction_session_timeout`. It ends a session left idle in an open transaction. It does not touch the job locks (idle outside a transaction) or the listener (outside the pool). `idle_session_timeout` is never set |
| `PHARMACY_DB_CONNECTION_TIMEOUT_MS` | `10000` | How long a request waits for a pooled connection before 503 `database_busy` |
| `PHARMACY_MAX_IN_FLIGHT` / `PHARMACY_MAX_IN_FLIGHT_WAIT_MS` | `100` / `250` | Requests in progress per instance, see above |
| `PHARMACY_ASYNC_THREADS` / `PHARMACY_ASYNC_QUEUE` | `8` / `10000` | The async reactions' pool. Keep the threads under half of `PHARMACY_DB_POOL_SIZE` |
| `PHARMACY_EDGE_ACCESS_CACHE_SECONDS` | `30` | The edge's access cache. `0` turns it off |

**Database (RDS PostgreSQL)**

- Point `PHARMACY_DB_URL` at the RDS instance or cluster endpoint, **not RDS Proxy**: the job
  locks (session advisory locks) and the listener (`LISTEN`) need a database session of their own,
  which RDS Proxy pins or does not support.
- `LISTEN/NOTIFY` works on RDS PostgreSQL as is; nothing to enable.

**Metrics and alarms**

- Scrape `http://<task>:8081/actuator/prometheus` with the ADOT collector (a sidecar in the task
  scraping `localhost:8081`, or a collector service in the VPC), exporting to Amazon Managed
  Service for Prometheus or to CloudWatch (EMF exporter).
- Metrics (Prometheus names):
  - jobs: `pharmacy_jobs_runs_total{job,outcome}`, `pharmacy_jobs_duration_seconds{job,outcome}`,
    `pharmacy_jobs_failures_total{job}`;
  - sign-in: `pharmacy_signin_failures_total{kind=account|unknown_email}`,
    `pharmacy_signin_locks_total{kind=account|unknown_email|client}`;
  - notifications: `pharmacy_notifications_queued_total{channel}`, `..._sent_total{channel}`,
    `..._failed_total{channel}`;
  - webhooks: `pharmacy_webhooks_deliveries_total{outcome=delivered|failed}`;
  - exports: `pharmacy_exports_total{outcome=ready|retried|failed|abandoned}`;
  - HTTP and the platform (built in): `http_server_requests_seconds`, `hikaricp_connections_*`,
    `jvm_*`, `process_*`.
- Alarms:
  - **a job failed**: a CloudWatch Logs metric filter on the API's log group,
    `{ $.message = "JOB_FAILED*" }` (JSON logs) or the term `"JOB_FAILED"` (plain text), alarm on
    ≥ 1 in 5 minutes; or `increase(pharmacy_jobs_runs_total{outcome="FAILED"}[5m]) > 0`;
  - **a nightly job did not run**: `sum(increase(pharmacy_jobs_runs_total{job="retention",outcome="OK"}[26h])) == 0`
    (likewise `subscription-lifecycle`); root sees the same at `GET /api/v1/platform/jobs`;
  - lock-outs: `increase(pharmacy_signin_locks_total{kind="client"}[15m])` above normal;
  - outbound: `pharmacy_notifications_failed_total`, `pharmacy_webhooks_deliveries_total{outcome="failed"}`;
  - errors: 5xx rate from `http_server_requests_seconds_count{status=~"5.."}` (or the ALB's own
    `HTTPCode_Target_5XX_Count`).
- Traces are not emitted by the API. If they are wanted, add the OpenTelemetry Java agent to the
  task (`JAVA_TOOL_OPTIONS=-javaagent:...`) sending OTLP to the same ADOT collector; no code change.

## Audit default partition (OPS-18)

An audit entry whose month has no partition lands in `audit.entries_default`. It should stay empty:
once it holds an entry of a month, that month's partition can no longer be created, and every later
entry of the month stays there. The `audit-partitions` job (start and 02:15 UTC) counts it:

- the gauge `pharmacy.audit.default_partition.rows` (alert when it is above 0);
- the log line `AUDIT_DEFAULT_PARTITION_ROWS <n>` and the job run marked FAILED.

To repair, as the application role or the owner, at a quiet moment (it holds an exclusive lock on
`audit.entries` for a few moments; writers wait):

```sql
select audit.default_partition_rows();     -- how many are trapped
select audit.repair_default_partition();   -- moves them into their months; returns how many moved
```

The function detaches the default partition, creates the partitions of the months its rows belong
to and a new empty default, copies every row back through the parent under its pharmacy's
row-level security, checks the count and drops the old table, all in one transaction (an error
changes nothing). With `PHARMACY_AUDIT_REPAIR_DEFAULT_PARTITION=true` the nightly job does it
itself. Find out first why the partitions were not created (`GET /api/v1/platform/jobs`).
