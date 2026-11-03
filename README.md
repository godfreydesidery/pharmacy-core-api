# pharmacy-core-api

The backend of **Pharmacy Core**: a multi-tenant, multi-branch pharmacy management system covering point of sale, inventory with batch/expiry control (FEFO), purchasing, customers and suppliers, accounting, reporting and integrations. It is one deployable **modular monolith** (Spring Modulith). Tenant isolation is enforced by **PostgreSQL row-level security**.

| | |
|---|---|
| Stack | Java 21, Spring Boot 4.1, Spring Modulith 2.1, Hibernate 7, PostgreSQL 17, Flyway, springdoc (OpenAPI 3.1) |
| Contract | [`pharmacy-core-docs/contracts/api-v1.md`](../pharmacy-core-docs/contracts/api-v1.md); the running app serves `/v3/api-docs` and `/swagger-ui.html` |
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
- **Events.** Modules publish through `DomainEventPublisher` inside their transaction. The Spring Modulith event publication registry (`platform.event_publication`) is the transactional outbox. Composition reactions (`@Async @TransactionalEventListener`) post to the ledger, update reporting read models and dispatch webhooks after commit, and are redelivered on restart.
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
| `integrations` | `integrations` | Outbound webhooks (CloudEvents 1.0, HMAC-SHA256 signed), delivery log |
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
- CORS allows `http://localhost:4200` (the Angular dev server).

To reset the local database, stop the app and delete `application/pharmacy-core/target/local-postgres`. Use `PHARMACY_LOCAL_DB_PORT` and `PHARMACY_LOCAL_DATA` to change the port and the data directory.

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
| `PHARMACY_DB_MIGRATIONS_ENABLED` | `true` | `false` when migrations run as a separate step (the API then holds no owner password) |
| `PHARMACY_DB_POOL_SIZE` | `20` | Hikari pool size |
| `PHARMACY_ROOT_EMAIL` / `PHARMACY_ROOT_PASSWORD` / `PHARMACY_ROOT_NAME` | — | First root administrator, created at start if none exists |
| `PHARMACY_CORS_ORIGINS` | — | Comma-separated allowed origins for the web app |
| `PHARMACY_SESSION_HOURS` | `12` | Session lifetime |
| `PHARMACY_SEED_DEMO` | `false` | Seed the demo tenant |
| `PHARMACY_PAYMENTS_PROVIDER` | `manual` | Payment authorization adapter |
| `PHARMACY_FISCAL_PROVIDER` | `none` | Fiscal receipt adapter |
| `PHARMACY_SWAGGER_ENABLED` | `true` | Serve `/v3/api-docs` and Swagger UI |
| `PHARMACY_LOG_FORMAT` | — | `ecs` or `logstash` for JSON logs |
| `PHARMACY_PORT` | `8080` | HTTP port |

Or use Docker: `docker compose up --build`. This starts PostgreSQL 17 with the roles from `deploy/postgres/init.sql`, then the API.

## Tests

```bash
mvn verify
```

- **Module unit tests** cover pure rules: totals, permission presets, postings balance.
- **`application/pharmacy-core` integration tests** run the whole app on a random port against an embedded PostgreSQL with the production roles. They include:
  - `PharmacyFlowTest`: root onboards two tenants, goods are received, a FEFO sale goes through, the ledger and dashboard catch up, and the tenants stay isolated;
  - `RowLevelSecurityTest`: every `tenant_uid` table has forced RLS, isolation fails closed, and the application role is unprivileged.
- **`architecture-tests`** contains `ModuleStructureTest` (Spring Modulith verify; it also writes module docs to `target/spring-modulith-docs`) and `CodeRulesTest` (ArchUnit).

## Conventions

- **Formatting.** Google Java Format, enforced by Spotless in `verify`. Run `mvn spotless:apply` before committing.
- **Identifiers.** Every record has a UUIDv7 `uid`, used in the API, events and other modules. Internal `id`s never leave a module.
- **Money and time.** Money is `numeric(14,2)` / `BigDecimal`, through `Amounts`. Times are UTC `timestamptz`.
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
5. Add the route to `pharmacy-core-docs/contracts/api-v1.md`.

## Integration points

| Integration | Where | Default | How to replace |
|---|---|---|---|
| Payment authorization (M-Pesa, Airtel Money, card terminals, insurers) | `sales.ports.outbound.PaymentAuthorizations` | `ManualPaymentAuthorizations` (approves, keeps the reference) | Add a `@Component` implementing the port with `@ConditionalOnProperty(name = "pharmacy.integrations.payments.provider", havingValue = "mpesa")`, then set `PHARMACY_PAYMENTS_PROVIDER=mpesa` |
| Fiscal receipts (TRA VFD/EFD, KRA eTIMS) | `sales.ports.outbound.FiscalReceipts` | `NoFiscalReceipts` (returns empty) | Same pattern, with `pharmacy.integrations.fiscal.provider` |
| Outbound events to any system | `integrations` module, `/api/v1/webhooks` | — | Register a webhook. Events arrive as CloudEvents 1.0 JSON, signed with `X-Pharmacy-Signature: sha256=<HMAC>` |
| External POS, ERP, e-commerce | The versioned REST API `/api/v1` | — | Use a session token and the tenant and branch headers, with `Idempotency-Key` on `POST /sales` for safe retries |

Adapters live in `application/pharmacy-core/src/main/java/tz/co/pharmacy/platform/composition`, the only place that binds a module to the outside world. A module never knows which provider it talks to.
