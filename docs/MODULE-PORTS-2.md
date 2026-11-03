# Module ports, part 2: fx, notifications, files, audit and security

Binding, like `MODULE-PORTS.md`, whose rules all still apply. Four forks build these areas in parallel. Signatures that cross a fork boundary are fixed here. Inside your own area, add whatever you need.

## Shared rules for this wave

- **Ownership.** Each fork owns the files listed for it. To change a file another fork may touch (the web shell, `EdgeFilter`, `AccessInterceptor`, existing module classes), use small `Edit` operations only, never `Write` on an existing file. Re-read before each edit, because the other forks edit in parallel.
- **Migrations.** Never edit an existing `V1__*.sql`. Add a new version in the module, with the name fixed for your fork:

| Migration | Fork |
|---|---|
| `V2__multi_currency.sql` | fx fork, in tenancy, catalogue, purchasing, sales, finance and reporting |
| `V2__notification_preferences.sql` | notifications fork, if it needs one in access |
| `V3__security.sql` | security fork, in access |
| `V1__audit_store.sql` | security fork, the new `audit` schema in `platform/src/main/resources/db/migration/audit` |
| `V1__fx_schema.sql`, `V1__notifications_schema.sql`, `V1__files_schema.sql` | the fx, notifications and files forks, in their new modules |

- **Already in place:** the Maven modules `modules/fx`, `modules/notifications` (which has `spring-boot-starter-mail`) and `modules/files`. Their delivery and pharmacy-core dependencies, and the schemas `fx`, `notifications`, `files` and `audit` in `ModuleMigrations`, are wired. The first build needs network for the mail starter, so build without `-o` once.
- **Builds.** Build only your own modules (`mvn -q -pl <modules> spotless:apply install -DskipTests`). Never run the pharmacy-core tests or the whole reactor; I integrate at the end. Web forks run `npm run build` in `pharmacy-core-web` to check their work, but not `npm test`/e2e (I run those).
- **Modern stack.**
  - Java 21 records, sealed interfaces and pattern-matching `switch` where they help.
  - Angular 21: signals, `@if`/`@for`, standalone components, zoneless.
  - Server-Sent Events for live notifications.
  - WebAuthn passkeys alongside TOTP.
  - Presigned URLs for files.
  - A runtime signal-based translation service.

## fx (`tz.co.pharmacy.modules.fx`)

```java
// ports.inbound
record CurrencyView(String code, String name, int minorUnits, String symbol) {}
record RateView(UUID uid, String base, String quote, BigDecimal rate, LocalDate effectiveOn, String source, Instant createdAt) {}
record RateCommand(String base, String quote, BigDecimal rate, LocalDate effectiveOn) {}
interface Currencies { List<CurrencyView> all(); Optional<CurrencyView> find(String code); }   // ISO 4217 catalogue (in code: at least TZS KES UGX RWF BIF USD EUR GBP ZAR CNY INR AED), minor units from java.util.Currency
interface ExchangeRates {                                    // current tenant
  /** Units of `quote` per 1 `base`, the latest rate effective on or before `on`; identity for base == quote.
      422 rate_missing (field "currency") when none. */
  BigDecimal rate(String base, String quote, LocalDate on);
  BigDecimal convert(BigDecimal amount, String from, String to, LocalDate on);   // rounded to `to`'s minor units
  Page<RateView> list(String quote, PageQuery query);
  RateView record(RateCommand c);                          // manual rate; source "MANUAL"
  List<RateView> refresh();                                // pulls from ports.outbound.RateProvider for the tenant's accepted currencies; source = provider name
}
// ports.outbound
interface RateProvider { String name(); Map<String, BigDecimal> latest(String base, Set<String> quotes); }
```

The default adapter is in composition: `NoRateProvider` returns an empty map. An `OpenExchangeRatesProvider` (an HTTP call to an exchangerate.host-style API) is selected by `pharmacy.integrations.fx.provider=openexchangerates` with `...fx.api-key`. A scheduled refresh runs every day at 06:00 UTC for every tenant that has accepted currencies; the scheduler lists tenants through the tenancy port and runs as a platform service per tenant.

Multi-currency rules, all built by the fx fork:
- **Base currency.** The tenant's `currency` is its base, also called the functional currency.
- **Accepted currencies.** `SettingsView` and `ChangeSettingsCommand` gain `acceptedCurrencies` (a `List<String>`; the base is always included). Add them as the last record components and update every call site.
- **Purchases.** `CreatePurchaseOrderCommand`, `RecordReceiptCommand` and their views gain `currency` (the default is base) and `exchangeRate` (base units per 1 document unit, fixed when the document is created). Their totals are in the document currency, and each view gains `baseTotal`. `GoodsReceived` gains `currency`, `exchangeRate` and `baseTotal`. Inventory unit costs are always in the base currency: receiving converts `unitCost × rate`.
- **Sales.** Prices stay in the base currency. A payment may be made in an accepted foreign currency: `PaymentCommand` and `PaymentView` gain `currency` and `exchangeRate`, with `amount` in the payment currency. Totals, payment coverage and change are computed in the base currency (change is always given in base). `SaleCompleted.Payment` gains `currency` and `baseAmount`.
- **Finance.** The ledger posts in the base currency only. Each journal line gains an optional `currency` and `foreignAmount` for reference. A goods receipt in a foreign currency is posted at its base value. Add account `7000 FX Gains/Losses` (EXPENSE type, used for rounding differences).
- **Reporting.** Reports are in the base currency (use the base amounts from events).
- **Web.** On purchase orders and receipts, a currency picker and the rate (prefilled from `/fx/rates/latest`, editable). On the POS, a payment currency per payment line with the converted amount shown. Plus an fx rates page under Finance and accepted currencies in settings.
- **Routes** (scope TENANT, permission `finance.view` / `finance.manage`):
  - `GET /api/v1/currencies`
  - `GET /api/v1/fx/rates?quote=`
  - `GET /api/v1/fx/rates/latest?base=&quote=&on=`
  - `POST /api/v1/fx/rates`
  - `POST /api/v1/fx/rates/refresh`
- **Contract.** Add a "Currencies and exchange rates" section to `pharmacy-core-docs/contracts/api-v1.md` and update the changed DTOs there.

## notifications (`tz.co.pharmacy.modules.notifications`)

```java
// ports.inbound
enum Channel { EMAIL, SMS, WHATSAPP, IN_APP }
record Recipient(UUID userUid, String name, String email, String phone, String locale) {}   // any of email/phone/userUid may be null
record NotificationRequest(String templateKey, Recipient recipient, Set<Channel> channels, Map<String, Object> params,
                           String dedupKey /* nullable; same key within 24h = sent once */) {}
interface Notifier {
  /** Queues one message per channel (skipping channels the recipient lacks an address for or opted out of), in the
      current tenant (or platform scope when no tenant: then tenant templates are not consulted). Sending happens after commit. */
  void send(NotificationRequest request);
}
record InAppView(UUID uid, String title, String body, String link, String severity, boolean read, Instant createdAt) {}
interface InAppInbox {                    // the current user in the current tenant
  Page<InAppView> list(boolean unreadOnly, PageQuery query);
  long unreadCount();
  void markRead(UUID uid);
  void markAllRead();
}
record TemplateView(String key, Channel channel, String locale, String subject, String body, boolean custom) {}
record TemplateCommand(String subject, String body) {}
interface Templates {                     // defaults built in (en + sw) for every key; a tenant may override
  List<TemplateView> list();
  TemplateView override(String key, Channel channel, String locale, TemplateCommand c);
  void reset(String key, Channel channel, String locale);
}
record PreferenceView(String category, Set<Channel> channels) {}
interface Preferences { List<PreferenceView> mine(); List<PreferenceView> change(List<PreferenceView> changes); }
record DeliveryView(UUID uid, String templateKey, Channel channel, String to, String status, String provider, int attempts,
                    String lastError, Instant createdAt, Instant sentAt) {}
interface DeliveryLog { Page<DeliveryView> list(Channel channel, String status, PageQuery query); }
// ports.outbound
interface EmailSender { String name(); void send(String to, String subject, String htmlBody, String textBody); }
interface SmsSender { String name(); void send(String toE164, String text); }
interface WhatsAppSender { String name(); void send(String toE164, String text); }
interface NotificationDirectory { List<Recipient> usersWithPermission(String permission, UUID branchUid /* nullable */); Optional<Recipient> user(UUID userUid); }
```

Template keys:
- `stock.low`, `stock.expiring`
- `sale.receipt`
- `daily.summary`
- `customer.overdue`
- `access.invitation`, `access.password_reset`, `access.new_sign_in`, `access.two_factor_code`
- `tenant.welcome`

Bodies use `{{param}}` placeholders and are HTML-escaped for email.

Adapters (in the notifications module, `providers` package) are chosen by property and registered `@ConditionalOnProperty`:

| Property | Values and settings |
|---|---|
| `pharmacy.notifications.email.provider` | `log` (default: logs only) or `smtp` (`JavaMailSender`, `spring.mail.*`, `pharmacy.notifications.email.from`) |
| `pharmacy.notifications.sms.provider` | `log` (default), `africastalking` (`...sms.africastalking.username` / `.api-key` / `.sender-id`, `POST https://api.africastalking.com/version1/messaging`, sandbox URL when `username=sandbox`), `twilio` (`...twilio.account-sid` / `.auth-token` / `.from`), or `beem` (`...beem.api-key` / `.secret-key` / `.sender-id`, `POST https://apisms.beem.africa/v1/send`) |
| `pharmacy.notifications.whatsapp.provider` | `log` (default) or `twilio` (`whatsapp:` from number) |

All provider calls use `java.net.http`, with 10 s timeouts and no logging of secrets.

The outbox:
- `notifications.messages` (RLS) records each message: status `QUEUED`/`SENT`/`FAILED`, attempts, next attempt at.
- An after-commit async sender, plus a `@Scheduled` retry every minute for due `QUEUED`/`FAILED` messages with attempts < 5, using exponential backoff. The retry must run per tenant. Add a platform-scope table `notifications.tenants_with_due` or use a SECURITY DEFINER function, as tenancy did for `branch_count`.

In-app delivery:
- `notifications.inbox` (RLS) holds the in-app messages.
- `GET /api/v1/notifications/stream` is a Server-Sent Events stream (`SseEmitter`) for the current user: it pushes `{unreadCount, latest}` when a new in-app message arrives. Send a heartbeat every 25 s.
- Because `EventSource` cannot send headers, the stream accepts `?token=&tenant=` query parameters. Add that to `EdgeFilter` with small edits, for this one path only.

Reactions, in composition:

| Event | Notification |
|---|---|
| `StockLow` | `stock.low` to users with `inventory.adjust` at that branch (in-app plus email) |
| `SaleCompleted` with a customer phone | `sale.receipt` SMS, if the tenant setting `smsReceipts` is true. Add `smsReceipts` as the last component of the tenancy settings records; the fx fork also edits those, so coordinate by `Edit` on the latest file. |
| `TenantRegistered` | `tenant.welcome` to the new admin |

Plus a scheduled 07:00 tenant-local expiry digest (`stock.expiring`) and an 18:00 `daily.summary`.

Routes:
- `/api/v1/notifications` (TENANT): GET the inbox, POST `/{uid}/read`, POST `/read-all`, GET `/unread-count`, GET `/stream`.
- `/api/v1/notification-preferences` (TENANT).
- `/api/v1/notification-templates` (`settings.manage`).
- `/api/v1/notification-deliveries` (`settings.manage`).

Web:
- a top-bar bell with an unread badge (live over SSE) and a dropdown panel;
- an Inbox page;
- "Notifications" under Administration (templates editor with a preview, deliveries log);
- preferences on the Account page.

Contract: add a "Notifications" section to `api-v1.md`.

## audit + security (platform audit, `access`, the edge)

```java
// platform.persistence.audit (new package in platform; OPEN like the rest of platform)
record AuditEntryView(UUID uid, Instant at, String actor, String action /* CREATE|UPDATE|DELETE|SIGN_IN|… */, String entityType,
                      UUID entityUid, UUID tenantUid, UUID branchUid, Map<String, Object> before, Map<String, Object> after, String correlationId) {}
interface AuditLog { void record(String action, String entityType, UUID entityUid, Map<String,Object> before, Map<String,Object> after);
                     Page<AuditEntryView> search(String entityType, UUID entityUid, String actor, LocalDate from, LocalDate to, PageQuery query); }
```

The audit store:
- `audit.entries`: append-only (refuse_change trigger, insert and select grants only). RLS: `tenant_uid = current` or, when `tenant_uid` is null, platform rows visible only to a session with no tenant. Partition by month with declarative partitioning, creating the current and next months in the migration plus a monthly `@Scheduled` job.
- Hibernate post-insert/update/delete listeners register for every `RecordedEntity` and write before/after values of changed properties, with secrets masked: any property named like `password`, `secret`, `token` or `hash`.
- Security events are written explicitly: sign-in, sign-in failure, lockout, 2FA, password change and reset, role or permission change, and root admin acting in a tenant.
- Route: `GET /api/v1/audit` with `entityType`, `entityUid`, `actor`, `from`, `to` and paging (permission `settings.manage`, TENANT; PLATFORM scope sees platform rows).
- Web: an Audit log page under Administration and a Platform → Audit page for the root admin. Show before and after as a diff.

Security, in `access` and the edge:
1. **Brute-force protection.** Per email and per IP, 5 failures in 15 min lock the account for 15 min (`423 account_locked`, `retryAfterSeconds`). A sliding-window rate limit at the edge for `/auth/*` gives `429 too_many_requests` with a `Retry-After` header. Implement it in-memory per node (bucket4j-style, no new dependency) behind a `RateLimiter` interface, ready for Redis later.
2. **TOTP 2FA** (RFC 6238, HMAC-SHA1, 30 s, 6 digits, ±1 step, implemented in-house):
   - `POST /auth/2fa/setup` returns the secret and an `otpauth://` URI; `POST /auth/2fa/enable {code}`; `POST /auth/2fa/disable {code}`.
   - Ten recovery codes, stored hashed.
   - Sign-in: when 2FA is on, `POST /auth/sign-in` returns `202 {challengeToken, methods:["TOTP","PASSKEY"]}` instead of a session, and `POST /auth/sign-in/verify {challengeToken, code}` returns the session.
   - A root admin may be forced to use 2FA by `pharmacy.access.require-2fa-for-root=true`.
3. **Passkeys (WebAuthn).** Use `com.yubico:webauthn-server-core` (add it to the access pom; fetching needs network). Routes:
   - `POST /auth/passkeys/register/options`, `POST /auth/passkeys/register` (signed in);
   - `POST /auth/passkeys/sign-in/options`, `POST /auth/passkeys/sign-in` (public, usernameless) returning the session;
   - `GET` and `DELETE /auth/passkeys`.
   - The RP id comes from `pharmacy.access.webauthn.rp-id` (default `localhost`) and the origins from `...rp-origins` (default `http://localhost:4200,http://localhost:4300`).
4. **Password reset.**
   - `POST /auth/password-reset {email}` is always `202`. It sends `access.password_reset` through `Notifier`, with a link `{webUrl}/reset-password?token=…` (`pharmacy.access.web-url`, default `http://localhost:4300`).
   - `POST /auth/password-reset/complete {token, newPassword}`.
   - The token is 32 random bytes, stored as a SHA-256 hash, valid 30 min and single use.
   - On success, all the user's sessions are revoked.
5. **Invitations.**
   - `POST /api/v1/users/invitations {name, email, roleUid, allBranches, branchUids}` (`users.manage`) sends `access.invitation`.
   - `POST /auth/invitations/accept {token, password}` (public) creates or activates the user and the membership and returns a session.
   - `GET /auth/invitations/{token}` (public) shows the tenant name and inviter.
   - The existing `addMember` with a password stays, for back-office creation.
6. **Session list.** `GET /auth/sessions` lists the user's sessions (created, last seen, user agent and IP; record these at sign-in) and `DELETE /auth/sessions/{uid}` revokes one. A sign-in from a new device sends `access.new_sign_in` (in-app plus email).
7. **Security headers.** The edge adds `X-Content-Type-Options`, `Referrer-Policy`, `X-Frame-Options DENY` and a strict `Content-Security-Policy` for API responses.

Access gets an outbound port `AccessNotices { void send(String templateKey, Recipient-like fields…) }`, or it calls `Notifier` through the composition adapter `AccessNotificationsAdapter`. Use notifications' `Notifier` exactly as specified above.

Web:
- the sign-in flow handles the 2FA challenge (code input, or "Use a passkey" via `navigator.credentials.get`);
- a passkey sign-in button on the sign-in page;
- forgot-password and reset-password pages;
- an accept-invitation page;
- an Account page with security sections: 2FA setup with the QR code (render the otpauth URI as a QR in-house, or with the small `qrcode` npm package), passkeys, sessions, and password;
- an Invite user flow on the Users page.

Contract: add "Security" and "Audit" sections to `api-v1.md`.

## files (`tz.co.pharmacy.modules.files`) and i18n infrastructure

```java
// ports.inbound
record FileView(UUID uid, String name, String contentType, long size, String ownerType, UUID ownerUid, String purpose,
                String sha256, Instant createdAt, String createdBy) {}
record UploadCommand(String name, String contentType, long size, String ownerType, UUID ownerUid, String purpose) {}
record UploadTicket(UUID fileUid, String uploadUrl, Map<String, String> headers, Instant expiresAt) {}   // presigned PUT
interface Files {                               // current tenant
  UploadTicket beginUpload(UploadCommand c);    // allowed types: image/png, image/jpeg, image/webp, application/pdf; ≤ 10 MB (413 file_too_large / 415 unsupported_type)
  FileView completeUpload(UUID fileUid);        // verifies the object exists, records size + sha256
  FileView uploadDirect(UploadCommand c, java.io.InputStream content);   // server-side upload (local adapter / small files)
  String downloadUrl(UUID fileUid);             // presigned GET, 5 min
  List<FileView> attachments(String ownerType, UUID ownerUid);
  void delete(UUID fileUid);
}
// ports.outbound
interface ObjectStorage { String name(); String presignPut(String key, String contentType, Duration ttl); String presignGet(String key, Duration ttl);
                          void put(String key, String contentType, java.io.InputStream content, long size); boolean exists(String key);
                          long size(String key); java.io.InputStream get(String key); void delete(String key); }
```

Storage adapters, in the files module, selected by `pharmacy.files.storage`:
- `local` (the default): a directory from `pharmacy.files.local.root`, default `target/files`. Its "presigned" URLs are HMAC-signed API URLs such as `/api/v1/files/blob/{key}?exp=&sig=`, and the files module serves PUT and GET for them.
- `s3`: S3-compatible (AWS, MinIO, Cloudflare R2) through AWS SDK v2 (`software.amazon.awssdk:s3`, using the bom; network needed), with presigned PUT and GET. Settings `pharmacy.files.s3.bucket`, `.region`, `.endpoint`, `.access-key`, `.secret-key`.

Object keys are `tenants/{tenantUid}/{yyyy}/{mm}/{fileUid}`.

Routes (TENANT, any permission for your own uploads; `settings.manage` for `ownerType=TENANT`):
- `POST /api/v1/files/uploads`, `POST /api/v1/files/uploads/{uid}/complete`, `POST /api/v1/files` (multipart, direct);
- `GET /api/v1/files/{uid}/download` (a 302 to the presigned URL);
- `GET /api/v1/files?ownerType=&ownerUid=`;
- `DELETE /api/v1/files/{uid}`.

The multipart limit is 10 MB: set `spring.servlet.multipart` in `application.yml` with a small edit.

Uses:
- the pharmacy logo (`ownerType=TENANT`, `purpose=LOGO`), on the Settings page and in the shell and on receipts;
- a prescription image (`ownerType=SALE`, `purpose=PRESCRIPTION`), from the POS prescription dialog, uploaded after the sale and shown on the sale detail;
- attachments on the purchase order and goods receipt detail pages (supplier invoice scans).

i18n web infrastructure, owned by the files fork:
- `core/i18n`: a `Translations` signal store, the `t` pipe, and dictionaries `en.ts` and `sw.ts` keyed as `area.key`;
- a language switch in the top bar and on the Account page, persisted to localStorage and sent as `Accept-Language`;
- `Intl` locale `sw-TZ` / `en-TZ` for numbers and dates;
- a lint script `npm run i18n:check` that lists missing keys.

The files fork converts only the shell, the sign-in pages, the workspace page and the dashboard to the `t` pipe. A later wave converts the rest.

The API reads `Accept-Language` for the notification locale (`sw` or `en`).

Contract: add a "Files" section to `api-v1.md`.

## product types, tracking modes and equipment (catalogue, inventory, purchasing, sales, checkout)

A pharmacy also sells equipment (wheelchairs, crutches, BP machines, nebulisers), medical supplies, cosmetics, supplements and baby care.

**catalogue** (migration `V3__product_types.sql`):

```java
enum ProductType { MEDICINE, MEDICAL_EQUIPMENT, MEDICAL_SUPPLY, COSMETIC, SUPPLEMENT, BABY_CARE, OTHER }
enum TrackingMode { BATCH_EXPIRY, BATCH, SERIAL, QUANTITY }   // default: MEDICINE → BATCH_EXPIRY, MEDICAL_EQUIPMENT → SERIAL, else BATCH
enum UnitOfMeasure { PIECE, BOX, PACK, STRIP, BOTTLE, TUBE, SACHET, VIAL, PAIR, SET, KIT, ROLL }
```

Changes to `ProductCommand` and `ProductView`, appended as the last components:
- `productType` (default MEDICINE), `trackingMode` (nullable, which means the default for the type), `unitOfMeasure` (default PIECE);
- `brand`, `model`, `warrantyMonths` (nullable, for equipment), `requiresSerialAtSale` (true for SERIAL).

Medicine-only fields (`genericName`, `atcCode`, `dosageForm`, `strength`, `schedule`) are required only when `productType == MEDICINE`; otherwise they are nullable, with `dosageForm` null and `schedule` OTC.

Categories gain `parentUid`, nullable, with a maximum depth of 3. Seed the default tree on the first `categories()` call for a tenant if it is empty:

| Parent | Children |
|---|---|
| Medicines | Analgesics, Antibiotics, Antimalarials, … |
| Medical Equipment | Mobility (wheelchairs, crutches, walkers), Diagnostics (BP machines, glucometers, thermometers), Respiratory (nebulisers, oxygen concentrators) |
| Medical Supplies | Dressings, Syringes & Needles, Gloves |
| Personal Care & Cosmetics | — |
| Supplements & Nutrition | — |
| Mother & Baby | — |

`CategoryView` gains `parentUid` and `path` ("Medical Equipment › Mobility"). `ProductFilter` gains `productType`, and a category filter includes its descendants.

`ProductChanged` gains `productType`, `trackingMode` and `unitOfMeasure`.

**inventory** (migration `V2__product_tracking.sql`):
- `ProductSnapshot` gains `productType`, `trackingMode` and `unitOfMeasure`.
- `BATCH` and `QUANTITY` modes have no expiry. Batches allow a null expiry; `QUANTITY` uses one implicit batch named `-`. FEFO for products without expiry is first-in, first-out by batch id.
- `SERIAL` mode adds a new `inventory.serial_units` table (RLS): product, batch, serial_number unique per tenant, branch, status (`IN_STOCK`, `SOLD`, `RETURNED`, `IN_TRANSIT`, `WRITTEN_OFF`), sale reference, and sold_at.
- `ReceiveStockCommand` gains `List<String> serialNumbers`. Its size must equal the quantity for SERIAL (422 `serials_mismatch`; 409 `duplicate_serial`).
- `allocate(productUid, quantity, reference)` keeps its signature. A new overload, `allocate(productUid, quantity, reference, List<String> serialNumbers)`, takes the chosen serials for SERIAL products (422 `serial_required`, `serial_not_in_stock`). `Allocation` gains `List<String> serialNumbers`.
- `restore` gains an overload with serials. Transfers carry serials for SERIAL products.
- Expiry views and summaries ignore products without expiry.
- `StockItemView` gains `productType` and `unitOfMeasure`. `StockFilter` gains `productType`.
- New route: `GET /api/v1/stock/{productUid}/serials?status=`.

**purchasing** (migration `V3__product_tracking.sql`): `ReceiptLineCommand` and its view gain `serialNumbers`. `expiryDate` is required only for BATCH_EXPIRY products; the product's tracking mode comes via `PurchasingDirectory` (add a method).

**sales and checkout** (migration `V3__serials.sql`):
- `CheckoutLine` gains `List<String> serialNumbers`, appended.
- `SaleLineCommand` and `SaleLineView` gain `serialNumbers`, and the sale line stores them in a child table.
- The receipt shows the serial numbers and the warranty end date (sale date + `warrantyMonths`).
- A return of a SERIAL line names the serials returned.
- `SellableProduct` gains `productType`, `trackingMode`, `unitOfMeasure` and `requiresSerialAtSale`.

**web:**
- The product form switches fields by type: the medicine section appears only for MEDICINE, and an equipment section (brand, model, warranty) for equipment.
- A category tree picker, plus a type filter on the product and stock lists.
- The POS asks for or scans a serial number when adding a SERIAL product.
- The goods-receipt line editor offers serial entry (paste one per line) and an optional expiry.
- A Serials tab in the stock batches drawer.
- Type icons, e.g. a wheelchair icon for equipment and a pill for medicine.

The demo seed (`DevelopmentSeed`, a small edit) adds a "Medical Equipment" range:
- Wheelchair Standard 18" (SERIAL, 12-month warranty, 3 units with serials WCH-0001…);
- a Digital BP Monitor (SERIAL);
- Crutches Pair (QUANTITY);
- Surgical Gloves Box (BATCH).

Contract: update `api-v1.md` with product types, tracking modes and serials.
