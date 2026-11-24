# pharmacy-core-api

The backend of **Pharmacy Core**: a multi-tenant, multi-branch pharmacy management system covering point of sale, inventory with batch/expiry control (FEFO), purchasing, customers and suppliers, accounting, reporting and integrations. It is one deployable **modular monolith** (Spring Modulith). Tenant isolation is enforced by **PostgreSQL row-level security**.

| | |
|---|---|
| Stack | Java 21, Spring Boot 4.1, Spring Modulith 2.1, Hibernate 7, PostgreSQL 17, Flyway, springdoc (OpenAPI 3.1) |
| Contract | [`docs/openapi.json`](docs/openapi.json) is the complete reference (OpenAPI 3.1, generated from the code, checked in the build); [`pharmacy-core-docs/contracts/api-v1.md`](../pharmacy-core-docs/contracts/api-v1.md) is the human guide. The running app serves `/v3/api-docs` and `/swagger-ui.html` |
| Module spec | [`docs/MODULE-PORTS.md`](docs/MODULE-PORTS.md): every module's ports, views and events (binding) |
| Architecture | [`pharmacy-core-docs/architecture`](../pharmacy-core-docs/architecture/overview.md) |

## Architecture in one page

```
platform/                 shared kernel: refusals, execution context, tenant-aware tx + RLS, migrations,
                          keyset paging, document numbers, domain event publisher (no module deps)
modules/<name>/           one Maven module per domain module; depends on pharmacy-platform ONLY
  ports.inbound           services, commands, views   ← what others may call
  ports.outbound          what the module needs from outside (implemented in composition)
  events                  past-tense facts (records implementing DomainEvent)
  domain, persistence,    internal: entities, repositories, service implementations
  application
application/onboarding    application services: one use case writing to several modules,
application/checkout        in one transaction, through those modules' ports.inbound
application/receiving
delivery/                 the HTTP edge (/api/v1): EdgeFilter, AccessInterceptor, ProblemAdvice, handlers
application/pharmacy-core the deployable: PharmacyApplication + platform/composition
                          (adapters: outbound port → another module's inbound port;
                           reactions: event → another module's inbound port)
architecture-tests/       Spring Modulith verify + ArchUnit rules
```

- **Compile-time boundaries.** A domain module's jar depends only on `pharmacy-platform`, so `javac` refuses an import of another module. Spring Modulith (`ModuleStructureTest`) verifies the named interfaces and that there are no cycles.
- **Multi-tenancy.** Every tenant table carries `tenant_uid` with a *forced* RLS policy, `tenant_uid = platform.current_tenant_uid()`. `TenantAwareTransactionManager` sets `pharmacy.tenant_uid` / `pharmacy.user_uid` with `set_config(..., true)` at the start of every transaction. With no tenant set, every tenant table is empty, so isolation fails closed. The application connects as a role that is `nosuperuser nobypassrls` and does not own the schemas. The app refuses to start otherwise.
- **Branches.** Requests name the tenant (`X-Tenant-Id`) and the branch (`X-Branch-Id`). The edge checks both against the user's membership (`allBranches` or a list of branches) before any handler runs. Branch-scoped data carries `branch_uid`.
- **Root administrator.** A platform-scope user (`access.users.root_admin`) who manages tenants (`/api/v1/platform/*`) and may act in any tenant and branch with every permission. Every action is recorded under their own identity.
- **Access control.** Every handler method declares `@Access(scope, permission)`. A method without one is refused (fail closed), and an ArchUnit rule checks this too.
- **Events.** Modules publish through `DomainEventPublisher` inside their transaction. The Spring Modulith event publication registry (`platform.event_publication`) is the transactional outbox. Composition reactions (`@Async @TransactionalEventListener`) post to the ledger, update reporting read models and dispatch webhooks after commit; an undelivered one is delivered again by the `event-resubmission` job (every 5 minutes, one instance).
- **Several instances.** Jobs run once across instances (PostgreSQL advisory locks, `SKIP LOCKED` claims) and record their runs in `platform.job_runs`; sign-in limits are shared in `platform.rate_windows`; live notifications reach any instance through `LISTEN/NOTIFY`. See [`docs/runbook/deployment.md`](docs/runbook/deployment.md).
- **Errors.** Refusals (`Refusal`) become RFC 9457 problems with a stable `reason`. Successes use the envelope `{ success, code, message, timestamp, data }`.

### Modules

| Module | Schema | Responsibility |
|---|---|---|
| `tenancy` | `tenancy` | Tenants (registry, no RLS), branches, tenant settings |
| `access` | `access` | Users, root administrators, sessions, memberships, roles and permissions |
| `catalogue` | `catalogue` | Products (medicines: ATC, GTIN, schedule), categories, manufacturers |
| `partners` | `partners` | Customers (retail, wholesale, insurance), suppliers |
| `inventory` | `inventory` | Batches, stock per branch, append-only movements, FEFO allocation, adjustments, transfers |
| `purchasing` | `purchasing` | Purchase orders, goods receipts |
| `sales` | `sales` | Sales, payments, prescriptions, returns |
| `finance` | `finance` | Expenses, chart of accounts, double-entry journal (idempotent postings) |
| `reporting` | `reporting` | Read models built from events; dashboard and reports |
| `integrations` | `integrations` | Outbound webhooks (CloudEvents 1.0, Standard Webhooks signatures), delivery log, payment methods |
| `onboarding` | — | Application service: register a tenant and its first administrator |
| `checkout` | — | Application service: POS sale (catalogue + FEFO allocation + sale) and returns |
| `receiving` | — | Application service: goods receipt + stock in |
| `delivery` | — | HTTP edge and `/api/v1` handlers |
| `composition` | — | Adapters, reactions, root admin bootstrap, demo seed, default integration adapters |

## Run locally (nothing to install but JDK 21 and Maven)

```bash
mvn -pl application/pharmacy-core -am spring-boot:run -Dspring-boot.run.profiles=local
```

The `local` profile does the following:
- starts an **embedded PostgreSQL** on port `54330`, with its data in `target/local-postgres` and the production roles;
- migrates every schema;
- creates the root administrator;
- seeds a demo tenant.

| Who | Email | Password |
|---|---|---|
| Root administrator | `root@pharmacy.local` | `Root#2026` |
| Afya Pharmacy (AFYA) admin | `admin@afya.co.tz` | `Afya#2026` |
| Afya cashier (HQ only) | `cashier@afya.co.tz` | `Cashier#2026` |

- API: `http://localhost:8080/api/v1`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- Health: `/actuator/health`
- Metrics: `/actuator/prometheus` (the `local` profile publishes it on the API port; on a server it is on `PHARMACY_MANAGEMENT_PORT` only)
- Scheduled jobs and their last runs (root): `GET /api/v1/platform/jobs`
- CORS allows `http://localhost:4200` (the Angular dev server).

To reset the local database, stop the app and delete `application/pharmacy-core/target/local-postgres`. Use `PHARMACY_LOCAL_DB_PORT` and `PHARMACY_LOCAL_DATA` to change the port and the data directory.

### On a simulated clock (`local,sim`)

All business time comes from one application `Clock`. Services inject it, entities use
`Clocks.now()`, and the build fails on a direct `Instant.now()`. Under the extra `sim` profile
that clock is simulated, and root moves it forward:

```bash
java -jar … --spring.profiles.active=local,sim --pharmacy.sim.start=2026-12-01T04:00:00Z
curl -X POST localhost:8080/api/v1/platform/sim/clock -H 'Authorization: Bearer …' \
     -H 'Content-Type: application/json' -d '{"advance":"P1D"}'
```

The `pharmacy-world` workspace uses this to play a whole market through a quarter and check the
books every night. The API refuses to start under `sim` without `local` (or an explicit
`PHARMACY_SIM_ALLOWED=true` on a dedicated demo service). See
`pharmacy-core-docs/runbook/simulated-world.md` and ADR 0011.

## Run against a real PostgreSQL

Create the two roles once, as a superuser:

```sql
create role pharmacy_owner login password 'change-me-owner';
create role pharmacy_app   login nosuperuser nobypassrls password 'change-me-app';
create database pharmacy owner pharmacy_owner;
```

Then set:

| Variable | Default | Meaning |
|---|---|---|
| `PHARMACY_DB_URL` | `jdbc:postgresql://localhost:5432/pharmacy` | JDBC URL |
| `PHARMACY_DB_USER` / `PHARMACY_DB_PASSWORD` | `pharmacy_app` / — | Application role (no RLS bypass) |
| `PHARMACY_DB_OWNER_USER` / `PHARMACY_DB_OWNER_PASSWORD` | `pharmacy_owner` / — | Owner role, used only to run migrations |
| `PHARMACY_MIGRATIONS_MODE` | `migrate-and-run` | `migrate-only` (migrate and exit 0: the one-off task before a deploy) or `run-only` (never migrate, hold no owner password, refuse to start while a migration is pending). See `docs/runbook/migrations.md` |
| `PHARMACY_MIGRATIONS_LOCK_TIMEOUT` / `PHARMACY_MIGRATIONS_STATEMENT_TIMEOUT` | `5s` / `5min` | A migration that waits longer for a lock fails and is rolled back |
| `PHARMACY_DB_MIGRATIONS_ENABLED` | `true` | Older switch: `false` means `run-only` |
| `PHARMACY_ACCESS_ENCRYPTIONKEY` | — | The legacy sealing key (id `legacy`), at least 32 characters |
| `PHARMACY_KEYS_<ID>` | — | Named keys of the keyring; `PHARMACY_SEALING_TOTP` / `_PROVIDERS` / `_WEBHOOKS` name each purpose's active key. See `docs/runbook/rotating-keys.md` |
| `PHARMACY_DB_POOL_SIZE` | `20` | Hikari pool size |
| `PHARMACY_DB_STATEMENT_TIMEOUT` / `PHARMACY_DB_LOCK_TIMEOUT` / `PHARMACY_DB_IDLE_IN_TRANSACTION_TIMEOUT` | `30s` / `5s` / `60s` | Set on every pooled connection. A job's transactions get `PHARMACY_DB_JOB_STATEMENT_TIMEOUT` (`5m`); a `@Transactional(timeout = ...)` gets its own |
| `PHARMACY_DB_CONNECTION_TIMEOUT_MS` | `10000` | Wait for a pooled connection, then 503 `database_busy` |
| `PHARMACY_MAX_IN_FLIGHT` / `PHARMACY_MAX_IN_FLIGHT_WAIT_MS` | `100` / `250` | Requests in progress per instance; past it, 503 `server_busy` with `Retry-After` |
| `PHARMACY_ASYNC_THREADS` / `PHARMACY_ASYNC_QUEUE` | `8` / `10000` | The bounded pool of the async event reactions |
| `PHARMACY_EDGE_ACCESS_CACHE_SECONDS` | `30` | The edge's per-instance cache of a session's pharmacy, membership and branch (`0`: off) |
| `PHARMACY_ROOT_EMAIL` / `PHARMACY_ROOT_PASSWORD` / `PHARMACY_ROOT_NAME` | — | First root administrator, created at start if none exists |
| `PHARMACY_CORS_ORIGINS` | — | Comma-separated allowed origins for the web app |
| `PHARMACY_SESSION_HOURS` | `12` | Session lifetime |
| `PHARMACY_SEED_DEMO` | `false` | Seed the demo tenant |
| `PHARMACY_PAYMENTS_PROVIDER` | `manual` | Payment authorization adapter |
| `PHARMACY_FISCAL_PROVIDER` | `none` | Fiscal document adapter: `none`, `simulator` (sim/local/test only) or a real adapter's value |
| `PHARMACY_FISCAL_JOB_CRON` / `PHARMACY_FISCAL_RESEND_AFTER` | `0 */15 * * * *` / `PT10M` | The fiscal job (`fiscal-documents`): resend PENDING documents, close past days with a Z report per branch |
| `PHARMACY_SWAGGER_ENABLED` | `true` | Serve `/v3/api-docs` and Swagger UI |
| `PHARMACY_REQUIRE_IF_MATCH` | `false` | `true`: a change of an edited resource (product, customer, supplier, role, branch, price list, settings) without `If-Match` is refused 428 `precondition_required`. A stale `If-Match` is always 412 `precondition_failed` |
| `PHARMACY_LOG_FORMAT` | — | `json` (or `ecs`) for one JSON object per line on stdout, Elastic Common Schema; `logstash`, `gelf` also work; unset is plain text |
| `PHARMACY_PORT` | `8080` | HTTP port |
| `PHARMACY_MANAGEMENT_PORT` | — | A separate port for actuator (health and `/actuator/prometheus`), e.g. `8081` on ECS; the API port keeps `/livez` and `/readyz`. Unset: actuator shares the API port and serves health only |
| `PHARMACY_INSTANCE_ID` | host name + random | This instance's name in job runs and cross-instance signals |
| `SENTRY_DSN` | — | Optional error tracker (Sentry); off when unset, no personal data sent |
| `PHARMACY_FILES_STORAGE` | `local` | `local` (a directory, one instance) or `s3` (AWS S3, MinIO, R2; any number of instances). See [`docs/runbook/files-on-s3.md`](docs/runbook/files-on-s3.md) |

Or use Docker: `docker compose up --build`. This starts PostgreSQL 17 with the roles from `deploy/postgres/init.sql`, then the API.

## Operations

- [`docs/runbook/files-on-s3.md`](docs/runbook/files-on-s3.md): file storage on S3, what AWS must provide, the move from a volume.
- [`docs/runbook/backup-restore.md`](docs/runbook/backup-restore.md): backup layers, RPO/RTO, restore procedures, drills.
- `ops/backup/`: `pg-dump-to-s3.sh` (encrypted logical backup to S3), `restore.sh` (restore into a new database and check it), `backup-role.sql`.
- `ops/files/check-records.sh`: every stored file the database lists is in the bucket.

## Tests

```bash
mvn verify
```

- **Module unit tests** cover pure rules: totals, permission presets, postings balance.
- **`application/pharmacy-core` integration tests** run the whole app on a random port against an embedded PostgreSQL with the production roles. They include:
  - `PharmacyFlowTest`: root onboards two tenants, goods are received, a FEFO sale goes through, the ledger and dashboard catch up, and the tenants stay isolated;
  - `RowLevelSecurityTest`: every `tenant_uid` table has forced RLS, isolation fails closed, and the application role is unprivileged.
  - `OpenApiDocumentTest`: `docs/openapi.json` is the document the code generates (see "The API contract" below).
- **`architecture-tests`** contains `ModuleStructureTest` (Spring Modulith verify; it also writes module docs to `target/spring-modulith-docs`) and `CodeRulesTest` (ArchUnit).

## Conventions

- **Formatting.** Google Java Format, enforced by Spotless in `verify`. Run `mvn spotless:apply` before committing.
- **Identifiers.** Every record has a UUIDv7 `uid`, used in the API, events and other modules. Internal `id`s never leave a module.
- **Money and time.** Money is `numeric(14,2)` / `BigDecimal`, through `Amounts`. `Amounts.money(x)` rounds to the pharmacy's base currency's ISO 4217 minor units (0 for UGX and RWF, 2 for TZS, KES and USD); an amount in another currency uses `Amounts.money(x, currency)`. One rounding mode everywhere, half-up (`Amounts.ROUNDING`), conversions included. A base currency of more than 2 minor units (KWD) is refused, `currency_not_supported`. Times are UTC `timestamptz`.
- **Edited resources.** A view of a row people edit implements `Versioned` (its `version`); its GET answers `ETag: "<version>"` and its PUT takes `@RequestHeader("If-Match")` and calls `Preconditions.ifMatch` inside the handler's transaction (412 `precondition_failed`; 428 when `pharmacy.http.require-if-match`).
- **Refusals.** Use `Refusal.invalid/notFound/conflict/forbidden(reason, detail)` with a snake_case `reason`. Validate input with `Checks`.
- **Migrations.** Append-only Flyway migrations per schema: `modules/<m>/src/main/resources/db/migration/<m>/V<n>__*.sql`. Never edit an applied migration.

### Adding a module

1. Create `modules/<name>/pom.xml`, depending on `pharmacy-platform` only. Add it to the root `<modules>` and `<dependencyManagement>`, and to the `delivery` and `pharmacy-core` dependencies.
2. Add `package-info.java` with `@ApplicationModule(displayName = "<name>")`. Add `ports/inbound`, `ports/outbound` and `events` packages annotated `@NamedInterface`.
3. Write the schema `db/migration/<name>/V1__<name>_schema.sql`. Give every tenant table the standard columns and the forced RLS policy (see the `TenantEntity` javadoc). Use `grant ... to ${application_role}`.
4. Add the schema to `ModuleMigrations.SCHEMAS`.
5. Write adapters for its outbound ports in `platform/composition`.
6. Add the module's name to `ModuleStructureTest.expectedModules`.
7. Document its ports in `docs/MODULE-PORTS.md`.

### Adding an endpoint

1. Add a handler in `delivery/src/main/java/tz/co/pharmacy/delivery/api/v1/<area>/`. Its methods return `ApiResponse<T>`.
2. Annotate every method with `@Access(scope = AccessScope.…, permission = "…")`:
   - `PUBLIC`: no session;
   - `AUTHENTICATED`: a session, no tenant;
   - `PLATFORM`: root administrator only;
   - `TENANT`: needs `X-Tenant-Id`;
   - `BRANCH`: needs `X-Tenant-Id` and `X-Branch-Id`;
   - `TENANT_OPTIONAL_BRANCH`: branch optional, for reports.
3. Lists take `cursor` and `limit` and return `Page<T>`.
4. Call modules only through their `ports.inbound`, or through an application service.
5. Add the route to `pharmacy-core-docs/contracts/api-v1.md`, and refresh `docs/openapi.json` (below).

### The API contract

`docs/openapi.json` is the OpenAPI 3.1 document the code serves at `/v3/api-docs`: every route, its body and answer, and what the edge enforces, read from the handler's annotations (`x-scope` and `x-permission` from `@Access`, with `x-permission-any-of`, `x-permission-all-of`, `x-step-up`; `x-idempotent`, `x-plan-feature`, `x-plan-limit`, `x-if-match`), the headers (`X-Tenant-Id`, `X-Branch-Id`, `Idempotency-Key`, `X-Approval`, `If-Match`) and the RFC 9457 problems (`components.schemas.Problem`). It is the complete reference; `api-v1.md` is the guide that explains the flows.

`OpenApiDocumentTest` fails the build (and CI) when the committed file differs from what the code generates. After changing a route, refresh it, review the difference and commit it:

```bash
mvn -pl application/pharmacy-core -am verify -Dtest=OpenApiDocumentTest -Dsurefire.failIfNoSpecifiedTests=false -Dpharmacy.openapi.write=true
```

## Integration points

| Integration | Where | Default | How to replace |
|---|---|---|---|
| Payment authorization (M-Pesa, Airtel Money, card terminals, insurers) | `sales.ports.outbound.PaymentAuthorizations` | `ManualPaymentAuthorizations` (approves, keeps the reference) | Add a `@Component` implementing the port with `@ConditionalOnProperty(name = "pharmacy.integrations.payments.provider", havingValue = "mpesa")`, then set `PHARMACY_PAYMENTS_PROVIDER=mpesa` |
| Fiscal documents (TRA VFD/EFD, KRA eTIMS): receipts, credit notes, daily Z reports | `sales.ports.outbound.FiscalReceipts` (`FiscalRequest`: TIN/VRN, lines with VAT category, payments, counters) | `NoFiscalReceipts` (`none`: no documents); `SimulatedFiscalReceipts` (`simulator`, sim/local/test profiles only) | Same pattern, with `pharmacy.integrations.fiscal.provider`; a TRA VFD adapter needs TRA's specification and the pharmacy's credentials |
| Outbound events to any system | `integrations` module, `/api/v1/webhooks` | — | Register a webhook (at most 10 per pharmacy). Events arrive as CloudEvents 1.0 JSON signed as [Standard Webhooks](https://www.standardwebhooks.com/) define: `webhook-id`, `webhook-timestamp`, `webhook-signature: v1,<base64 HMAC-SHA256 of "id.timestamp.body">` with the `whsec_` secret. `POST /webhooks/{uid}/rotate-secret` issues a new secret; both sign for 24 h. The old `X-Pharmacy-Signature: sha256=<hex>` is still sent for one release |
| External POS, ERP, e-commerce | The versioned REST API `/api/v1` | — | Use a session token and the tenant and branch headers, with `Idempotency-Key` on `POST /sales` for safe retries |

Adapters live in `application/pharmacy-core/src/main/java/tz/co/pharmacy/platform/composition`, the only place that binds a module to the outside world. A module never knows which provider it talks to.
