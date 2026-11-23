# Module ports: the binding spec

Every module's public surface, shared by everyone who builds or calls a module. **Signatures here are binding**: implement them exactly as written (names, parameter order, record components), so that modules, application services, delivery and composition, written in parallel, compile together. To add something, add it to your own module and note it in your report. Never change another module's port.

Base package `tz.co.pharmacy`. Module `X` lives in `tz.co.pharmacy.modules.X` with these packages:

| Package | Visibility | Content |
|---|---|---|
| `X` (`package-info.java`) | — | `@ApplicationModule(displayName = "X")` |
| `X.ports.inbound` | `@NamedInterface("inbound")` | Service interfaces, commands, views (records), enums used in them |
| `X.ports.outbound` | `@NamedInterface("outbound")` | Interfaces the module needs from outside, implemented in composition |
| `X.events` | `@NamedInterface("events")` | Event records: `record Foo(EventMetadata metadata, …) implements DomainEvent` |
| `X.domain` | internal | JPA entities (extend `TenantEntity`, or `MutableEntity` for platform-scope rows) |
| `X.persistence` | internal | Spring Data repositories (`JpaRepository` + `JpaSpecificationExecutor`) |
| `X.application` | internal | `@Service` implementations of the inbound ports, `@Transactional` |

Rules:
- A module's Maven module depends on `pharmacy-platform` only. It never imports another module.
- Migrations go in `modules/X/src/main/resources/db/migration/X/V1__X_schema.sql`, in schema `X`. Every tenant table carries `tenant_uid`, the standard columns and the RLS policy (see `TenantEntity` javadoc). Use `grant ... to ${application_role}`. Use `text` for strings, `numeric(14,2)` for money, `numeric(5,2)` for percents, `integer` for quantities, `timestamptz` and `date`, `uuid` for uids. Unique constraints that are per tenant include `tenant_uid`.
- Hibernate runs with `ddl-auto: validate`, so the entity mapping must match the SQL exactly. Map enums with `@Enumerated(EnumType.STRING)` to `text` columns. Map child collections with `@OneToMany(mappedBy=…, cascade=ALL, orphanRemoval=true)` and `@ManyToOne(fetch=LAZY) @JoinColumn(name="…_id")`. Child rows are `TenantEntity` too.
- Use `Refusal.*` for every rule violation, with a stable snake_case `reason`. Use `Checks.*` for input validation. Use `Amounts.*` for money arithmetic.
- Paged lists newest first use `Keyset.page(repo, spec, query, mapper)`. Other orders use offset paging (`PageQuery.offset()`, `Page.offset(rows, query)`).
- Publish events with `DomainEventPublisher.publish("type.name", meta -> new Foo(meta, …))` inside the writing transaction.
- The current branch is `ExecutionContext.requireBranch()`. The current tenant is `ExecutionContext.requireTenant()` (or `TenantContext`). A report's branches are `ExecutionContext.reportBranches()`, where `null` means all.
- Document numbers come from `DocumentNumbers.format(prefix, digits)`, e.g. `format("INV-" + branchCode, 6)` gives `INV-HQ-000123`.
- Every service method that writes is `@Transactional`. Read methods are `@Transactional(readOnly = true)`.

Composition (`application/pharmacy-core/src/main/java/tz/co/pharmacy/platform/composition`):
- **Adapters.** `@Component class <Consumer><Provider>Adapter implements <consumer>.ports.outbound.Y` calls the provider's `ports.inbound`. The module that owns the outbound port writes the adapter.
- **Reactions.** `@Component class <Target>Reactions` with methods `@Async @TransactionalEventListener void on(SomeEvent e) { EventScopes.run(e.metadata(), "<service>", () -> target.port(...)); }`. Use one listener method per target module, so a failure in one does not hold the others.

Delivery (`delivery/src/main/java/tz/co/pharmacy/delivery/api/v1/<area>/`):
- `@RestController @RequestMapping("/api/v1/...")` classes.
- Every method is annotated `@Access(scope = AccessScope.X, permission = "...")` and returns `ApiResponse<T>` (`ApiResponse.ok(data, "…")`, or `ApiResponse.created(...)` with `@ResponseStatus(HttpStatus.CREATED)`).
- Request bodies are records in the handler file (or `<Area>Requests.java`) and are mapped to the module's commands. Responses are the module's views directly when they match the contract, otherwise a response record.
- Lists return `ApiResponse<Page<View>>`, with paging from `@RequestParam(required=false) String cursor, Integer limit` and `Pages.of(cursor, limit)`.
- The JSON contract is `pharmacy-core-docs/contracts/api-v1.md`.

---

## tenancy (`tz.co.pharmacy.modules.tenancy`)

Tables: `tenancy.tenants` is a platform-scope registry with **no RLS**: `code` unique, `name`, `legal_name`, `country`, `currency`, `timezone`, `status`, `status_reason`. `tenancy.branches` (RLS) and `tenancy.settings` (RLS, one row per tenant).

```java
// ports.inbound
enum TenantStatus { ACTIVE, SUSPENDED }
record TenantView(UUID uid, String code, String name, String legalName, String country, String currency,
                  String timezone, TenantStatus status, String statusReason, int branchCount, Instant createdAt) {}
record BranchView(UUID uid, String code, String name, String address, String phone, String licenceNumber,
                  boolean headOffice, boolean active, Instant createdAt) {}
record BranchCommand(String code, String name, String address, String phone, String licenceNumber) {}
record RegisterTenantCommand(String code, String name, String legalName, String country, String currency,
                             String timezone, BranchCommand headOffice) {}
record SettingsView(String currency, String timezone, BigDecimal defaultTaxRatePercent, String invoicePrefix,
                    String receiptFooter, BigDecimal maxDiscountPercent, int expiryAlertDays, boolean lowStockAlerts) {}
record ChangeSettingsCommand(BigDecimal defaultTaxRatePercent, String invoicePrefix, String receiptFooter,
                             BigDecimal maxDiscountPercent, Integer expiryAlertDays, Boolean lowStockAlerts) {}
record PlatformCounts(long tenants, long activeTenants, long suspendedTenants) {}

interface TenantService {
  /** Platform scope. Writes the registry row, then (via TenantSwitch) the head office branch and default
      settings as the new tenant. Publishes tenant.registered. Refuses a duplicate code: 409 duplicate_code. */
  TenantView register(RegisterTenantCommand command);
  Optional<TenantView> find(UUID tenantUid);          // registry read, any scope
  Page<TenantView> list(String q, PageQuery query);   // platform scope; branchCount may be 0 when not in tenant scope
  TenantView suspend(UUID tenantUid, String reason);
  TenantView reactivate(UUID tenantUid);
  PlatformCounts counts();
}
interface BranchService {                                // current tenant (RLS)
  List<BranchView> list();
  Optional<BranchView> find(UUID branchUid);
  BranchView create(BranchCommand command);
  BranchView change(UUID branchUid, BranchCommand command, boolean active);
}
interface SettingsService {                              // current tenant
  SettingsView current();
  SettingsView change(ChangeSettingsCommand command);
}
// events
record TenantRegistered(EventMetadata metadata, UUID tenantUid, String code, String name) implements DomainEvent {}  // "tenant.registered"
```
Note: `register` runs at platform scope (no tenant). The `TenantRegistered` metadata must carry the new tenant: publish it inside the `TenantSwitch.within(...)` block.

## access (`tz.co.pharmacy.modules.access`)

Tables:
- `access.users`: platform scope, **no RLS**. `email` unique in lower case, `name`, `password_hash` (Argon2id via spring-security-crypto `Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()`), `root_admin`, `active`, `last_sign_in_at`.
- `access.sessions`: no RLS. `token_hash` (SHA-256 hex), `user_uid`, `expires_at`, `revoked_at`.
- `access.roles` (RLS) plus `access.role_permissions(role_id, permission)`. Reading: the tenant's roles, the roles of the current user's own memberships elsewhere, and the role named on an invitation whose token the transaction names. Writing (insert, update) is the current tenant's only (access V18, ISO-7). The child's select policy is `exists (select 1 from access.roles r where r.id = role_id)`; its insert and delete policies also need `r.tenant_uid = platform.current_tenant_uid()`.
- `access.memberships`. Reading is `tenant_uid = platform.current_tenant_uid() or user_uid = platform.current_user_uid()`; insert and update are the current tenant's only. `access.membership_branches(membership_id, branch_uid)` follows the same split, through `access.memberships`.
- `access.session_pharmacies(session_uid, tenant_uid, entered_at)`: platform scope, no RLS, and **no privilege for the application role**. The application reaches it only through the SECURITY DEFINER functions `access.enter_pharmacy(session uuid, tenant uuid, at timestamptz)` (a live session only) and `access.entered_pharmacies(session_id bigint)` (ISO-11).

Tokens are `"pcs_" + base64url(32 random bytes)` and last 12 h (`pharmacy.access.session-hours`, default 12).

```java
// ports.inbound
record PermissionView(String code, String area, String description) {}
final class Permissions {   // the catalogue: constants + ALL list + TENANT_ALL (all but platform.manage) + presets per default role
  public static final String PLATFORM_MANAGE = "platform.manage"; /* … every code of contract §3 … */
  public static List<PermissionView> catalogue();
  public static Set<String> preset(String roleCode); // ADMIN, BRANCH_MANAGER, PHARMACIST, CASHIER, STORE_KEEPER, ACCOUNTANT
}
record UserView(UUID uid, String name, String email, boolean rootAdmin, boolean active, Instant lastSignInAt, Instant createdAt) {}
record Principal(UUID userUid, String name, String email, boolean rootAdmin) {}
record MembershipGrant(UUID membershipUid, UUID tenantUid, UUID roleUid, String roleCode, String roleName,
                       Set<String> permissions, boolean allBranches, Set<UUID> branchUids, boolean active) {}
record SignInResult(String token, Instant expiresAt, Principal principal) {}
record RoleView(UUID uid, String code, String name, String description, boolean system, List<String> permissions) {}
record RoleCommand(String code, String name, String description, List<String> permissions) {}
record MemberView(UUID membershipUid, UUID userUid, String name, String email, UUID roleUid, String roleCode, String roleName,
                  boolean allBranches, List<UUID> branchUids, boolean active, Instant lastSignInAt, Instant createdAt) {}
record AddMemberCommand(String name, String email, String password, UUID roleUid, boolean allBranches, List<UUID> branchUids) {}
record ChangeMemberCommand(UUID roleUid, boolean allBranches, List<UUID> branchUids, Boolean active /* null keeps it */, �) {}
record NewUserCommand(String name, String email, String password) {}

interface AuthenticationService {
  SignInResult signIn(String email, String password);   // 401 invalid_credentials; updates last_sign_in_at
  Optional<Principal> authenticate(String token);       // empty when unknown, expired, revoked, or user inactive
  void signOut(String token);
  void changePassword(UUID userUid, String currentPassword, String newPassword);
  /** The user's memberships in every tenant. Must be called OUTSIDE a transaction; it reads with
      TenantContext.callAsUser(userUid, ...). */
  List<MembershipGrant> memberships(UUID userUid);
}
interface MembershipResolver {
  /** The user's membership in the tenant (call with TenantContext set to the tenant and user), or empty. */
  Optional<MembershipGrant> resolve(UUID userUid, UUID tenantUid);
}
interface UserAdministration {                // current tenant unless stated
  Page<MemberView> members(String q, PageQuery query);
  MemberView addMember(AddMemberCommand command);   // new email: creates the user; existing: adds a membership (409 already_a_member)
  MemberView changeMember(UUID membershipUid, ChangeMemberCommand command);
  List<RoleView> roles();
  RoleView createRole(RoleCommand command);
  RoleView changeRole(UUID roleUid, RoleCommand command);   // a system role keeps its code and name
  /** Seeds the default system roles in the current tenant and makes the user (new or existing by email) its ADMIN. */
  MemberView seedTenant(NewUserCommand administrator);
  /** Active members plus invitations still open: the seats the plan's users cap counts (INT-6). */
  long seatsInUse();
  /** Names as this pharmacy may show them: a former member as it kept them when they left (ISO-6). */
  Map<String, String> displayNames(Collection<String> references);
  Map<UUID, String> displayNamesByUid(Collection<UUID> userUids);
  // platform scope
  List<UserView> rootAdmins();
  UserView createRootAdmin(NewUserCommand command);
  boolean anyRootAdmin();
  long userCount();
}
// ports.outbound
/** The plan's users cap (composition: tenancy Entitlements.requireRoomGiven). Access counts the seats
    in the transaction that takes one, under a per-pharmacy transaction-scoped advisory lock, so
    parallel requests cannot pass the cap: an invitation, a managed login, a member added or
    reactivated (members + open invitations + 1), and an invitation accepted (members + 1; an
    invitation a root administrator sent is never held back). 422 plan_limit_reached. */
interface UsersCap { void requireRoom(UUID tenantUid, long inUse, long adding); }

/** The one-time "Apply recommended roles" notice (owner, 23 Nov 2026). Current tenant. Decisions per
    Permissions.PRESETS_VERSION in access.role_preset_decisions. */
interface RolePresets {
  Recommendations recommendations();
  Recommendations apply();     // built-in roles := presets; audited; 409 last_admin; 403 permission_not_held
  Recommendations dismiss();   // keep the pharmacy's roles
  record Recommendations(String presetsVersion, boolean showNotice, boolean dismissed, String decision,
                         Instant decidedAt, String decidedBy, List<RoleChange> roles) {}
  record RoleChange(UUID roleUid, String code, String name, List<String> add, List<String> remove) {}
}
```

## onboarding (`tz.co.pharmacy.application.onboarding`, application service)

```java
record OnboardTenantCommand(RegisterTenantCommand tenant, NewUserCommand administrator) {}
interface TenantOnboarding { TenantView onboard(OnboardTenantCommand command); }
```
`onboard` is `@Transactional`. It calls `tenantService.register(...)`, then `tenantSwitch.within(uid, () -> userAdministration.seedTenant(admin))`.

## catalogue (`tz.co.pharmacy.modules.catalogue`)

```java
// ports.inbound
enum DosageForm { TABLET, CAPSULE, SYRUP, SUSPENSION, INJECTION, INHALER, CREAM, OINTMENT, GEL, DROPS, SACHET, SUPPOSITORY, DEVICE, OTHER }
enum Schedule { OTC, PRESCRIPTION, CONTROLLED }
enum Storage { ROOM_TEMPERATURE, COOL_DRY, REFRIGERATED }
record CategoryView(UUID uid, String name, String description, Instant createdAt) {}
record CategoryCommand(String name, String description) {}
record ManufacturerView(UUID uid, String name, String country, Instant createdAt) {}
record ManufacturerCommand(String name, String country) {}
record ProductCommand(String name, String genericName, String sku, String gtin, String atcCode, UUID categoryUid,
                      UUID manufacturerUid, DosageForm dosageForm, String strength, String packSize, Schedule schedule,
                      BigDecimal sellingPrice, BigDecimal costPrice, BigDecimal taxRatePercent, Integer reorderLevel, Storage storage) {}
record ProductView(UUID uid, String name, String genericName, String sku, String gtin, String atcCode,
                   UUID categoryUid, String categoryName, UUID manufacturerUid, String manufacturerName,
                   DosageForm dosageForm, String strength, String packSize, Schedule schedule,
                   BigDecimal sellingPrice, BigDecimal costPrice, BigDecimal taxRatePercent, int reorderLevel,
                   Storage storage, boolean active, long version, Instant createdAt) {}
record ProductFilter(String q, UUID categoryUid, Boolean active) {}

interface CatalogueService {
  List<CategoryView> categories();
  CategoryView createCategory(CategoryCommand c);
  CategoryView changeCategory(UUID uid, CategoryCommand c);
  List<ManufacturerView> manufacturers();
  ManufacturerView createManufacturer(ManufacturerCommand c);
  ManufacturerView changeManufacturer(UUID uid, ManufacturerCommand c);
  Page<ProductView> products(ProductFilter filter, PageQuery query);  // ordered by name (offset paging)
  Optional<ProductView> product(UUID productUid);
  List<ProductView> products(Collection<UUID> productUids);
  ProductView createProduct(ProductCommand c);                      // publishes product.changed; 409 duplicate_sku
  ProductView changeProduct(UUID uid, ProductCommand c, boolean active, long version); // 409 stale_version; publishes product.changed
}
// events
record ProductChanged(EventMetadata metadata, UUID productUid, String name, String genericName, String sku,
                      String dosageForm, String strength, UUID categoryUid, String categoryName,
                      int reorderLevel, BigDecimal sellingPrice, boolean active) implements DomainEvent {}  // "product.changed"
// PricingService (Batch 4): PricedLine += UUID promotionUid; savePromotion of an active promotion deeper than the
// pharmacy's maxDiscountPercent needs approval DEEP_PROMOTION (amount = percentOff); deletePromotion is replaced by
// PromotionView switchOffPromotion(UUID uid) (never deleted; V11 revokes delete from the application role).
// ports.outbound: PricingSettings { BigDecimal maxDiscountPercent(); }
```

Batch 4 (the owner's controls of 23 Nov 2026, FRD-6):
```java
// CatalogueService
//   changeProduct: a change of schedule needs approvals.require(SCHEDULE_CHANGE) (once per transaction,
//   so one approval covers a whole product import) and is audited as SCHEDULE_CHANGED (before/after).
List<ProductView> controlledRegisterProducts();   // CONTROLLED now or ever (products.ever_controlled, V12)
boolean everControlled(UUID productUid);
```

## partners (`tz.co.pharmacy.modules.partners`)

```java
enum CustomerType { RETAIL, WHOLESALE, INSURANCE }
enum PaymentTerms { COD, NET_15, NET_30, NET_45, NET_60 }
record CustomerCommand(String name, String phone, String email, CustomerType type, String insuranceProvider,
                       String insuranceNumber, BigDecimal creditLimit, String address, Boolean active) {}
record CustomerView(UUID uid, String code, String name, String phone, String email, CustomerType type,
                    String insuranceProvider, String insuranceNumber, BigDecimal creditLimit, String address,
                    boolean active, Instant createdAt) {}
record SupplierCommand(String name, String contactPerson, String phone, String email, String tin,
                       PaymentTerms paymentTerms, String address, Boolean active) {}
record SupplierView(UUID uid, String code, String name, String contactPerson, String phone, String email, String tin,
                    PaymentTerms paymentTerms, String address, boolean active, Instant createdAt) {}
interface CustomerService {
  Page<CustomerView> search(String q, CustomerType type, PageQuery query);   // keyset
  Optional<CustomerView> find(UUID uid);
  CustomerView create(CustomerCommand c);   // code CUS-000001 via DocumentNumbers.format("CUS", 6)
  CustomerView change(UUID uid, CustomerCommand c);
  long count();
}
interface SupplierService {
  Page<SupplierView> search(String q, PageQuery query);
  Optional<SupplierView> find(UUID uid);
  SupplierView create(SupplierCommand c);   // SUP-000001
  SupplierView change(UUID uid, SupplierCommand c);
}
```

## inventory (`tz.co.pharmacy.modules.inventory`)

Tables:
- `inventory.products`: a local snapshot of catalogue products, unique `(tenant_uid, product_uid)`.
- `inventory.batches`: unique `(tenant_uid, product_uid, batch_number)`.
- `inventory.stock_levels`: unique `(tenant_uid, branch_uid, batch_id)`.
- `inventory.movements`: append-only (trigger `platform.refuse_change`, no update grant).
- `inventory.transfers` and `inventory.transfer_lines`.

FEFO allocation locks the branch's stock rows of the product with `@Lock(PESSIMISTIC_WRITE)`, earliest expiry first, and skips expired batches (`expiry_date < current_date`).

```java
// ports.inbound
enum MovementType { OPENING, RECEIPT, SALE, SALE_RETURN, ADJUSTMENT, TRANSFER_OUT, TRANSFER_IN }
enum AdjustmentReason { DAMAGE, THEFT_LOSS, COUNT_CORRECTION, EXPIRED_WRITE_OFF, SAMPLE, OTHER }
enum StockStatus { IN_STOCK, LOW_STOCK, OUT_OF_STOCK }
enum TransferStatus { IN_TRANSIT, RECEIVED }
record ProductSnapshot(UUID productUid, String name, String genericName, String sku, String dosageForm, String strength,
                       UUID categoryUid, String categoryName, int reorderLevel, BigDecimal sellingPrice, boolean active) {}
record ReceiveStockCommand(UUID productUid, String batchNumber, LocalDate expiryDate, int quantity, BigDecimal unitCost,
                           MovementType type /* OPENING or RECEIPT */, String reference) {}
record Allocation(UUID batchUid, String batchNumber, LocalDate expiryDate, int quantity, BigDecimal unitCost) {}
record AdjustStockCommand(UUID productUid, UUID batchUid, int quantityChange, AdjustmentReason reasonCode, String note) {}
record StockFilter(String q, StockStatus status, UUID categoryUid) {}
record StockItemView(UUID productUid, String productName, String genericName, String sku, String dosageForm, String strength,
                     String categoryName, int quantity, int reorderLevel, StockStatus status, LocalDate nearestExpiry,
                     int batchCount, BigDecimal sellingPrice, BigDecimal costValue) {}
record StockSummaryView(long products, long inStock, long lowStock, long outOfStock, long expiringSoon, long expired,
                        BigDecimal costValue, BigDecimal retailValue) {}
record BatchView(UUID uid, UUID productUid, String batchNumber, LocalDate expiryDate, int quantity, BigDecimal unitCost,
                 long daysLeft, boolean expired) {}
record ExpiringBatchView(UUID batchUid, UUID productUid, String productName, String batchNumber, LocalDate expiryDate,
                         long daysLeft, int quantity, BigDecimal costValue) {}
record MovementView(UUID uid, UUID productUid, String productName, UUID batchUid, String batchNumber, MovementType type,
                    int quantityChange, int balanceAfter, String reference, AdjustmentReason reasonCode, String note,
                    Instant createdAt, String createdBy) {}
record TransferLineCommand(UUID productUid, int quantity) {}
record TransferCommand(UUID toBranchUid, String note, List<TransferLineCommand> lines) {}
record TransferLineView(UUID productUid, String productName, UUID batchUid, String batchNumber, LocalDate expiryDate, int quantity) {}
record TransferView(UUID uid, String number, UUID fromBranchUid, String fromBranchName, UUID toBranchUid, String toBranchName,
                    TransferStatus status, String note, List<TransferLineView> lines, Instant createdAt, Instant receivedAt) {}

interface ProductSnapshots { void upsert(ProductSnapshot snapshot); }   // composition calls it on product.changed
interface StockService {                                  // current branch = ExecutionContext.requireBranch()
  void receive(ReceiveStockCommand command);              // creates the batch if new; records a movement; ensures the snapshot via ports.outbound.ProductCatalogue
  MovementView openingStock(ReceiveStockCommand command); // type OPENING
  /** FEFO at the current branch; 422 insufficient_stock (field null) when short; never an expired batch. Records SALE movements with reference. */
  List<Allocation> allocate(UUID productUid, int quantity, String reference);
  void restore(UUID batchUid, int quantity, String reference);   // SALE_RETURN at the current branch
  MovementView adjust(AdjustStockCommand command);         // 422 insufficient_stock if it would go below 0; publishes stock.adjusted
  void lockProducts(Collection<UUID> productUids);         // the per-product locks allocate takes, all at once in uid order (until the tx ends)
  TransferView dispatch(TransferCommand command);          // from the current branch, FEFO; 422 same_branch
  TransferView receiveTransfer(UUID transferUid);          // current branch must be the destination: 403 not_destination
}
interface StockQueries {
  Page<StockItemView> stock(StockFilter filter, PageQuery query);            // current branch, by product name (offset)
  StockSummaryView summary(Set<UUID> branchUids /* null = all */, int expiryAlertDays);
  List<BatchView> batches(UUID productUid);                                  // current branch, quantity > 0
  Page<ExpiringBatchView> expiring(Set<UUID> branchUids, int withinDays, PageQuery query);  // earliest first (offset)
  Page<MovementView> movements(UUID productUid, MovementType type, PageQuery query);         // current branch, keyset
  Map<UUID, Integer> onHand(Collection<UUID> productUids);                   // current branch
  Page<TransferView> transfers(String direction /* OUTGOING|INCOMING|null */, PageQuery query);
}
// ports.outbound
interface ProductCatalogue { Optional<ProductSnapshot> product(UUID productUid); }   // adapter → CatalogueService.product
interface BranchNames { String name(UUID branchUid); }                              // adapter → BranchService.find
// events
record StockAdjusted(EventMetadata metadata, UUID productUid, String productName, UUID batchUid, int quantityChange,
                     String reasonCode, int balanceAfter) implements DomainEvent {}   // "stock.adjusted"
record StockLow(EventMetadata metadata, UUID productUid, String productName, int quantity, int reorderLevel)
    implements DomainEvent {}   // "stock.low", published by allocate/adjust when a product drops to or below its reorder level
```

Batch 4 (the owner's stock controls of 23 Nov 2026, FRD-2, 5, 11, 12, 15):
```java
// StockService
//   adjust: a reduction needs a note (422 note_required) and approvals: CONTROLLED_WRITE_OFF for a
//     controlled medicine (always), else STOCK_WRITE_OFF above the limit, at the batch's cost.
//   openingStock: LATE_OPENING_STOCK (always) once the branch has a SALE, RECEIPT or TRANSFER_IN
//     movement (once per transaction for an import); 422 batch_cost_differs into a batch held at
//     another cost (never averaged).
TransferView receiveTransfer(UUID transferUid, ReceiveTransferCommand command); // receiveTransfer(uid) = in full
//   422 receiver_is_sender (root exempt); per-line receivedQuantity; what is short comes back on the
//   sender's books (TRANSFER_OUT +n, reference "TRF-… SHORT") and is written off there (THEFT_LOSS)
//   through adjust, so stock.adjusted posts the loss against the sending branch; note required;
//   TRANSFER_SHORTFALL above the limit, CONTROLLED_WRITE_OFF for a controlled medicine.
record ReceiveTransferCommand(String note, List<ReceivedLine> lines) {
  record ReceivedLine(UUID batchUid, UUID productUid, Integer receivedQuantity, List<String> serialNumbers) {} }
// TransferView + dispatchedBy, receivedBy, daysInTransit; TransferLineView + receivedQuantity
// StockQueries
Page<TransferView> overdueTransfers(String direction, int days, PageQuery query); // IN_TRANSIT > days; 422 invalid_days
// StockCounts: views of an OPEN count are blind (expected, variance, varianceValue, lossValue null;
//   blind = true); review(uid) shows them; post needs STOCK_WRITE_OFF above the limit on the count's
//   loss at cost, CONTROLLED_WRITE_OFF when any controlled medicine is short.
StockCountView review(UUID uid);
// Destructions: note required; approved as a write-off for its total; the approving manager is the
//   witness (DestructionView.witnessName, witnessApproved, approvedByUid, typedWitnessName).
// Transformations: the recipe is followed exactly (no_recipe, recipe_mismatch, inputs_below_formula);
//   422 schedule_downgrade when a prescription or controlled input makes a product of a lower class.
// ports.outbound
interface ProductCatalogue { … String schedule(UUID productUid); }           // adapter → CatalogueService.product
interface Recipes { Optional<PackLink> packOf(UUID unit); List<PackLink> linksOfPack(UUID pack);
                    List<Ingredient> formula(UUID product); }                  // adapter → PackagingService
```

## purchasing (`tz.co.pharmacy.modules.purchasing`)

```java
enum PurchaseOrderStatus { ORDERED, PARTIALLY_RECEIVED, RECEIVED, CANCELLED }
record OrderLineCommand(UUID productUid, int quantity, BigDecimal unitCost) {}
record CreatePurchaseOrderCommand(UUID supplierUid, LocalDate expectedDate, String note, List<OrderLineCommand> lines) {}
record PurchaseOrderLineView(UUID uid, UUID productUid, String productName, int quantity, int receivedQuantity, BigDecimal unitCost, BigDecimal lineTotal) {}
record PurchaseOrderView(UUID uid, String number, UUID branchUid, UUID supplierUid, String supplierName, PurchaseOrderStatus status,
                         LocalDate expectedDate, String note, BigDecimal total, List<PurchaseOrderLineView> lines, Instant createdAt, String createdBy) {}
record ReceiptLineCommand(UUID productUid, String batchNumber, LocalDate expiryDate, int quantity, BigDecimal unitCost) {}
record RecordReceiptCommand(UUID purchaseOrderUid, UUID supplierUid, String supplierInvoiceNumber, String note, List<ReceiptLineCommand> lines) {}
record ReceiptLineView(UUID productUid, String productName, String batchNumber, LocalDate expiryDate, int quantity, BigDecimal unitCost, BigDecimal lineTotal) {}
record GoodsReceiptView(UUID uid, String number, UUID branchUid, UUID purchaseOrderUid, String purchaseOrderNumber, UUID supplierUid,
                        String supplierName, String supplierInvoiceNumber, String note, BigDecimal total, List<ReceiptLineView> lines,
                        Instant createdAt, String createdBy) {}
interface PurchaseOrderService {     // current branch
  PurchaseOrderView create(CreatePurchaseOrderCommand c);   // number PO-<branchCode>-000001 (branch code via ports.outbound.PurchasingDirectory)
  PurchaseOrderView cancel(UUID uid, String reason);        // 422 not_cancellable unless ORDERED
  Optional<PurchaseOrderView> find(UUID uid);
  Page<PurchaseOrderView> list(PurchaseOrderStatus status, UUID supplierUid, PageQuery query);
}
interface GoodsReceiptService {      // current branch
  GoodsReceiptView record(RecordReceiptCommand c);   // validates the PO (same branch and supplier, not CANCELLED/RECEIVED, quantity ≤ outstanding: 422 over_receipt); updates the PO; GRN-<branchCode>-000001; publishes goods.received
  Optional<GoodsReceiptView> find(UUID uid);
  Page<GoodsReceiptView> list(UUID supplierUid, PageQuery query);
  BigDecimal purchasesTotal(Set<UUID> branchUids, LocalDate from, LocalDate to);   // by receipt date (UTC)
}
// ports.outbound
interface PurchasingDirectory { String supplierName(UUID supplierUid); /* 422 supplier_not_found */
                                String productName(UUID productUid);   /* 422 product_not_found */
                                String branchCode(UUID branchUid); }
// events
record GoodsReceived(EventMetadata metadata, UUID receiptUid, String number, UUID branchUid, UUID supplierUid, String supplierName,
                     BigDecimal total, List<Line> lines) implements DomainEvent {   // "goods.received"
  public record Line(UUID productUid, String productName, int quantity, BigDecimal unitCost) {}
}
```

## receiving (`tz.co.pharmacy.application.receiving`, application service)

```java
interface Receiving { GoodsReceiptView receive(RecordReceiptCommand command); }
```
`receive` is `@Transactional`. It calls `goodsReceiptService.record(c)`, then `stockService.receive(new ReceiveStockCommand(..., MovementType.RECEIPT, receipt.number()))` for each line.

## sales (`tz.co.pharmacy.modules.sales`)

```java
enum PaymentMethod { CASH, CARD, MOBILE_MONEY, INSURANCE, CREDIT }
enum SaleStatus { COMPLETED, PARTIALLY_RETURNED, RETURNED }
record PrescriptionDetails(String prescriberName, String prescriberRegistration, String reference, String patientName) {}
record PaymentCommand(PaymentMethod method, BigDecimal amount, String reference) {}
/** One priced line per batch allocation; checkout builds them. */
record SaleLineCommand(UUID productUid, String productName, boolean prescriptionRequired, UUID batchUid, String batchNumber,
                       LocalDate expiryDate, int quantity, BigDecimal unitPrice, BigDecimal unitCost, BigDecimal taxRatePercent) {}
record RecordSaleCommand(String number, String idempotencyKey, UUID customerUid, String customerName, BigDecimal discountPercent,
                         List<SaleLineCommand> lines, List<PaymentCommand> payments, PrescriptionDetails prescription) {}
record SaleLineView(UUID uid, UUID productUid, String productName, UUID batchUid, String batchNumber, LocalDate expiryDate,
                    int quantity, int returnedQuantity, BigDecimal unitPrice, BigDecimal discount, BigDecimal tax, BigDecimal lineTotal) {}
record PaymentView(PaymentMethod method, BigDecimal amount, String reference) {}
record SaleView(UUID uid, String number, UUID branchUid, String branchName, UUID customerUid, String customerName, SaleStatus status,
                BigDecimal discountPercent, BigDecimal subtotal, BigDecimal discountTotal, BigDecimal taxTotal, BigDecimal total,
                BigDecimal paidTotal, BigDecimal changeDue, List<SaleLineView> lines, List<PaymentView> payments,
                PrescriptionDetails prescription, String fiscalReference, String cashierName, Instant createdAt) {}
record SaleSummaryView(UUID uid, String number, String customerName, int itemCount, BigDecimal total, BigDecimal paidTotal,
                       List<PaymentMethod> paymentMethods, SaleStatus status, String cashierName, Instant createdAt) {}
record SaleFilter(String q, SaleStatus status, LocalDate from, LocalDate to) {}
record ReturnLineCommand(UUID saleLineUid, int quantity) {}
record RecordReturnCommand(UUID saleUid, String reason, PaymentMethod refundMethod, List<ReturnLineCommand> lines) {}
record SaleReturnLineView(UUID saleLineUid, UUID productUid, String productName, UUID batchUid, String batchNumber, int quantity, BigDecimal refund) {}
record SaleReturnView(UUID uid, String number, UUID saleUid, String saleNumber, String reason, PaymentMethod refundMethod,
                      BigDecimal refundTotal, /* foreign refund fields */ List<SaleReturnLineView> lines, Instant createdAt,
                      String createdBy, List<SaleRefundView> refunds) {}
record SaleRefundView(PaymentMethod method, BigDecimal amount, String reference) {}  // one share of the refund
// PaymentCommand also carries BigDecimal authorizedAmount (checkout only: a confirmed push payment whose excess
// went on account is verified for the confirmed amount; null = amount).

interface SaleService {            // current branch for writes and lists
  String nextSaleNumber();         // "<invoicePrefix>-<branchCode>-000123" (prefix and branch code via ports.outbound.SalesSettings)
  Optional<SaleView> findByIdempotencyKey(String key);
  /** Totals: per line gross = unitPrice*qty, discount = gross*discountPercent%, tax = (gross-discount)*taxRate%,
      lineTotal = gross-discount+tax. Refuses: 422 prescription_required (any prescriptionRequired line without
      prescription), 422 discount_not_allowed (discount>0 without ExecutionContext.permits("sales.discount") or above
      maxDiscountPercent), 422 customer_required (CREDIT/INSURANCE without customer), 422 payment_short
      (sum(payments) < total). changeDue = paid - total (cash only). Calls ports.outbound.PaymentAuthorizations for every
      non-cash payment and ports.outbound.FiscalReceipts after saving. Publishes sale.completed. */
  SaleView record(RecordSaleCommand command);
  Optional<SaleView> find(UUID uid);
  Page<SaleSummaryView> list(SaleFilter filter, PageQuery query);   // keyset
  List<SaleSummaryView> recent(Set<UUID> branchUids, int limit);
  /** 422 return_exceeds_sold; updates returnedQuantity and status; SRN-<branchCode>-000001; publishes sale.returned.
      The refund goes back the way the sale was paid (domain.RefundSplit, cumulative per tender, the last return of a
      sale takes exactly what is left): cash share in cash, MOBILE_MONEY/CARD/BANK_TRANSFER/CHEQUE by that method,
      CREDIT/ACCOUNT_CREDIT to the customer's account, VOUCHER back onto the voucher, INSURANCE off the claim.
      refundMethod is optional; when named it must be a way the sale was paid other than INSURANCE (CREDIT and
      ACCOUNT_CREDIT both mean the account, never for a walk-in): 422 refund_method_not_allowed. A line's last units
      refund lineTotal − already refunded (and the same for tax). */
  SaleReturnView recordReturn(RecordReturnCommand command);
  Page<SaleReturnView> returns(PageQuery query);
}
// ports.outbound
interface SalesSettings { String invoicePrefix(); BigDecimal maxDiscountPercent(); String branchCode(UUID branchUid); String branchName(UUID branchUid); }
interface PaymentAuthorizations { /** Integration point for M-Pesa, card terminals and insurers; the default adapter approves with the given reference. */
  PaymentAuthorization authorize(PaymentMethod method, BigDecimal amount, String reference, String saleNumber);
  record PaymentAuthorization(boolean approved, String reference, String declineReason) {} }  // declined → 422 payment_declined
interface FiscalReceipts { /** Integration point for the tax authority (e.g. TRA VFD); the default adapter returns empty. */
  Optional<String> issue(String saleNumber, BigDecimal total, BigDecimal taxTotal); }
// events
record SaleCompleted(EventMetadata metadata, UUID saleUid, String number, UUID branchUid, UUID customerUid, BigDecimal subtotal,
                     BigDecimal discountTotal, BigDecimal taxTotal, BigDecimal total, BigDecimal changeDue,
                     List<Line> lines, List<Payment> payments) implements DomainEvent {      // "sale.completed"
  public record Line(UUID productUid, String productName, int quantity, BigDecimal net, BigDecimal tax, BigDecimal cost) {}
  public record Payment(String method, BigDecimal amount) {}
}
record SaleReturned(EventMetadata metadata, UUID returnUid, String number, UUID saleUid, UUID branchUid, String refundMethod,
                    BigDecimal refundTotal, List<SaleCompleted.Line> lines, /* foreign refund fields */
                    List<Refund> refunds) implements DomainEvent {   // "sale.returned"
  public record Refund(String method, BigDecimal amount) {}  // null refunds (older events): refundMethod took it all
}
// The ledger posts one return entry per refund method (the first keyed by returnUid, the others by
// nameUUIDFromBytes(returnUid + "/" + method)).
```

**Owner's controls (Batch 4, 23 Nov 2026).** Added to the above:
```java
enum ReturnDisposition { SHELF, DAMAGED, QUARANTINE }
record ReturnLineCommand(UUID saleLineUid, int quantity, List<String> serialNumbers, ReturnDisposition disposition) {}
// SaleLineCommand += String schedule, UUID promotionUid, String promotionName (the class at the moment of sale and the
//   promotion that priced the line); SaleLineView += the same three.
// RecordSaleCommand += String pricePlanSource (CUSTOMER | CHOSEN | null); SaleView += pricePlanSource.
// SaleReturnLineView += UUID uid, ReturnDisposition disposition, int heldQuantity, ReturnDisposition releasedOutcome,
//   Instant releasedAt, String releasedBy.
interface SaleService {
  /** record: 409 shift_required (setting requireOpenShift, the caller has no open shift here); a discount above
      maxDiscountPercent needs approval DEEP_DISCOUNT (amount = the percent), still with sales.discount. */
  /** recordReturn: 409 shift_required; 422 restock_not_allowed (PRESCRIPTION/CONTROLLED, by the line's schedule, else the
      product's class now, to SHELF); disposition null = SHELF for OTC, QUARANTINE otherwise (heldQuantity = quantity);
      after returnWindowDays (local calendar) approval LATE_RETURN (amount = refundTotal), else REFUND above the limit. */
  ReleasedLine releaseReturnLine(UUID returnUid, UUID lineUid, ReturnDisposition outcome);  // SHELF | DAMAGED, whole line
  record ReleasedLine(SaleReturnView saleReturn, UUID productUid, UUID batchUid, int quantity, List<String> serialNumbers,
                      String reference /* "<return number>/Q<n>" */, ReturnDisposition outcome) {}
  List<SaleReturnView> heldReturns();   // the branch's returns with units in quarantine
}
// Shifts.close: |counted − expected| above the approval limit needs SHIFT_VARIANCE. Vouchers.issue: 409 shift_required.
// ports.outbound: SalesSettings += int returnWindowDays(), boolean requireOpenShift();
//   SalesProducts { Map<UUID, String> schedules(Collection<UUID> productUids); }   (catalogue, for lines sold before V12)
// events: SaleReturned += BigDecimal heldCost (cost of the units held in quarantine: the ledger books cost − heldCost
//   back to 1200); new SaleReturnReleased(metadata, returnUid, lineUid, number, branchUid, productUid, productName,
//   quantity, cost, outcome, reference) "sale.return_released": the ledger posts Dr 1200 / Cr 5000 cost, keyed by lineUid.
```

## checkout (`tz.co.pharmacy.application.checkout`, application service)

```java
record CheckoutLine(UUID productUid, int quantity) {}
record CheckoutCommand(String idempotencyKey, UUID customerUid, BigDecimal discountPercent, List<CheckoutLine> lines,
                       List<PaymentCommand> payments, PrescriptionDetails prescription) {}
record SellableProduct(UUID productUid, String name, String genericName, String sku, String gtin, DosageForm dosageForm,
                       String strength, UUID categoryUid, String categoryName, Schedule schedule, BigDecimal sellingPrice,
                       BigDecimal taxRatePercent, int quantityOnHand) {}
interface Checkout {
  SaleView checkout(CheckoutCommand command);          // @Transactional; an existing idempotencyKey returns that sale
  SaleReturnView returnSale(RecordReturnCommand command); // sales.recordReturn, then per line: SHELF restore;
                                                          // DAMAGED restore + adjust(DAMAGE); QUARANTINE nothing
  SaleReturnView releaseReturnLine(UUID returnUid, UUID lineUid, ReturnDisposition outcome); // restore (+ DAMAGE)
  Page<SellableProduct> sellable(String q, UUID categoryUid, PageQuery query);  // catalogue.products(active) + inventory.onHand
  /** Priced exactly as checkout prices the sale (plan, branch prices, promotions, breaks); 422 member_not_of_customer. */
  CoverageQuote coverageQuote(UUID memberUid, List<CheckoutLine> lines, String preauthNumber,
                              UUID customerUid, String pricePlan, BigDecimal discountPercent);
}
```
`checkout` does the following:
1. Validates the lines (`Checks.notEmpty`, quantity > 0; 422 `product_not_found` / `product_inactive`).
2. Refuses a `memberUid` not of the customer (422 `member_not_of_customer`), prices the cart, then takes every
   product's stock lock (lines and their pack products) in uid order with `stockService.lockProducts(...)`,
   so tills selling the same products in another order queue instead of deadlocking. A lock lost anyway
   (deadlock 40P01, lock timeout, serialization failure) is 409 `busy_try_again` with `Retry-After`.
   Then takes the number from `saleService.nextSaleNumber()`.
3. Runs `stockService.allocate(product, qty, number)` for each line.
4. Builds one `SaleLineCommand` per allocation (prices from the catalogue, tax rate = the product's, `prescriptionRequired` = schedule != OTC).
5. Takes the customer name from `CustomerService.find` (422 `customer_not_found`).
6. Calls `saleService.record(...)`.

## finance (`tz.co.pharmacy.modules.finance`)

```java
enum AccountType { ASSET, LIABILITY, EQUITY, REVENUE, EXPENSE }
enum ExpenseCategory { RENT, UTILITIES, SALARIES, TRANSPORT, MAINTENANCE, MARKETING, SUPPLIES, LICENCES, OTHER }
record ExpenseCommand(ExpenseCategory category, String description, BigDecimal amount, LocalDate expenseDate, String paymentMethod, String reference) {}
record ExpenseView(UUID uid, String number, UUID branchUid, ExpenseCategory category, String description, BigDecimal amount,
                   LocalDate expenseDate, String paymentMethod, String reference, Instant createdAt, String createdBy) {}
record AccountView(String code, String name, AccountType type, BigDecimal balance) {}
record JournalLineView(String accountCode, String accountName, BigDecimal debit, BigDecimal credit) {}
record JournalEntryView(UUID uid, String number, LocalDate entryDate, String sourceType, String sourceReference, String description,
                        UUID branchUid, List<JournalLineView> lines, Instant createdAt) {}
record SalePosting(UUID saleUid, String number, UUID branchUid, LocalDate date, BigDecimal net, BigDecimal tax, BigDecimal cost,
                   BigDecimal changeDue, Map<String, BigDecimal> paymentsByMethod) {}
record ReturnPosting(UUID returnUid, String number, UUID branchUid, LocalDate date, BigDecimal net, BigDecimal tax, BigDecimal cost, String refundMethod) {}
record ReceiptPosting(UUID receiptUid, String number, UUID branchUid, LocalDate date, BigDecimal total) {}
interface ExpenseService {
  ExpenseView record(ExpenseCommand c);   // current branch; EXP-000001; posts its journal entry now; publishes expense.recorded
  Page<ExpenseView> list(ExpenseCategory category, LocalDate from, LocalDate to, PageQuery query);
}
interface Ledger {
  List<AccountView> accounts();           // seeds the default chart on first use (idempotent)
  Page<JournalEntryView> journal(LocalDate from, LocalDate to, PageQuery query);
  /** Idempotent per (sourceType, sourceUid): a redelivered event posts nothing new. */
  void postSale(SalePosting p);
  void postReturn(ReturnPosting p);
  void postReceipt(ReceiptPosting p);
}
// events
record ExpenseRecorded(EventMetadata metadata, UUID expenseUid, String number, UUID branchUid, String category, BigDecimal amount,
                       LocalDate expenseDate) implements DomainEvent {}   // "expense.recorded"
```
The default chart of accounts:

| Code | Account | Type |
|---|---|---|
| 1000 | Cash | ASSET |
| 1010 | Bank | ASSET |
| 1020 | Mobile Money | ASSET |
| 1100 | Accounts Receivable | ASSET |
| 1110 | Insurance Receivable | ASSET |
| 1200 | Inventory | ASSET |
| 2000 | Accounts Payable | LIABILITY |
| 2100 | Tax Payable | LIABILITY |
| 3000 | Owner's Equity | EQUITY |
| 4000 | Sales Revenue | REVENUE |
| 4100 | Sales Returns | REVENUE |
| 5000 | Cost of Goods Sold | EXPENSE |
| 6000 | Rent | EXPENSE |
| 6010 | Utilities | EXPENSE |
| 6020 | Salaries | EXPENSE |
| 6030 | Transport | EXPENSE |
| 6040 | Maintenance | EXPENSE |
| 6050 | Marketing | EXPENSE |
| 6060 | Supplies | EXPENSE |
| 6070 | Licences | EXPENSE |
| 6090 | Other Expenses | EXPENSE |

Payment methods map to accounts: `CASH` → 1000, `CARD` → 1010, `MOBILE_MONEY` → 1020, `INSURANCE` → 1110, `CREDIT` → 1100.

Expenses and supplier payments are paid only from money accounts: `CASH` → 1000, `BANK_TRANSFER`, `CARD`, `CHEQUE` → 1010, `MOBILE_MONEY` → 1020; any other method is 422 `invalid_payment_method`. Their dates default to today in the pharmacy's time zone and may not be later (422 `future_date`). A supplier payment naming goods receipts settles each up to what is still open of it (`finance.supplier_payment_allocations`), else 422 `overpayment`.

```java
// reconciliation (MON-13)
record UnpostedDocument(String sourceType, UUID sourceUid, String number, UUID branchUid, Instant recordedAt) {}
interface LedgerReconciliation { List<UnpostedDocument> unposted(int days); }   // GET /finance/reconciliation; 422 invalid_days outside 1-92
// ports.outbound, implemented in composition (LedgerSourcesAdapter)
interface LedgerSources { List<UnpostedDocument> recorded(Instant from, Instant before); }
```

## reporting (`tz.co.pharmacy.modules.reporting`)

Read models via `JdbcTemplate` or `JdbcClient` upserts, all with RLS:
- `reporting.daily_sales`
- `reporting.daily_product_sales`
- `reporting.daily_payments`
- `reporting.daily_purchases`
- `reporting.daily_expenses`
- `reporting.processed_events` (`(tenant_uid, event_id)` primary key, for idempotency)

The day is the local date in the tenant's timezone, from `ports.outbound.ReportingTenant`.

```java
record SaleFact(UUID eventId, Instant occurredAt, UUID branchUid, BigDecimal gross /* subtotal */, BigDecimal discount,
                BigDecimal tax, BigDecimal net /* total - tax */, BigDecimal cost, List<ProductFact> products, Map<String, BigDecimal> payments, int sign /* +1 sale, -1 return */) {}
record ProductFact(UUID productUid, String productName, int quantity, BigDecimal net, BigDecimal cost) {}
record PurchaseFact(UUID eventId, Instant occurredAt, UUID branchUid, BigDecimal total) {}
record ExpenseFact(UUID eventId, LocalDate date, UUID branchUid, String category, BigDecimal amount) {}
interface Projections { void sale(SaleFact f); void purchase(PurchaseFact f); void expense(ExpenseFact f); }
record Metric(BigDecimal value, BigDecimal previous, BigDecimal changePercent) {}
record TrendPoint(LocalDate date, BigDecimal total) {}
record TopProduct(UUID productUid, String productName, long quantity, BigDecimal net) {}
record PaymentMix(String method, BigDecimal amount, long count) {}
record StockFigures(long inStock, long lowStock, long outOfStock, long expiringSoon, long expired) {}
record DashboardView(String currency, int periodDays, Metric sales, Metric purchases, Metric grossProfit, Metric transactions,
                     long customers, StockFigures stock, List<TrendPoint> salesTrend, List<TopProduct> topProducts,
                     List<PaymentMix> paymentMix, List<Object> expiringBatches, List<Object> recentSales) {}
record SalesReportRow(LocalDate date, long invoices, BigDecimal gross, BigDecimal discount, BigDecimal tax, BigDecimal net, BigDecimal cost, BigDecimal profit) {}
record SalesReport(List<SalesReportRow> rows, SalesReportRow totals) {}
record ProductReportRow(UUID productUid, String productName, long quantity, BigDecimal net, BigDecimal cost, BigDecimal profit) {}
record ExpenseLine(String category, BigDecimal amount) {}
record ProfitLoss(BigDecimal revenue, BigDecimal costOfSales, BigDecimal grossProfit, List<ExpenseLine> expenses, BigDecimal totalExpenses, BigDecimal netProfit) {}
interface Reports {                          // branches = ExecutionContext.reportBranches()
  DashboardView dashboard(int days);
  SalesReport sales(LocalDate from, LocalDate to);
  List<ProductReportRow> products(LocalDate from, LocalDate to, int limit);
  ProfitLoss profitLoss(LocalDate from, LocalDate to);
  List<PaymentMix> payments(LocalDate from, LocalDate to);
}
// ports.outbound (adapters in composition)
interface ReportingTenant { ZoneId zone(); String currency(); int expiryAlertDays(); }   // current tenant → TenantService/SettingsService
interface DashboardFacts { StockFigures stock(Set<UUID> branchUids, int expiryAlertDays);
                           List<Object> expiringBatches(Set<UUID> branchUids, int withinDays, int limit);  // ExpiringBatchView items
                           List<Object> recentSales(Set<UUID> branchUids, int limit);                      // SaleSummaryView items
                           long customers(); }
```

## integrations (`tz.co.pharmacy.modules.integrations`)

```java
enum DeliveryStatus { PENDING, DELIVERED, FAILED }
record WebhookCommand(String name, String url, List<String> eventTypes, Boolean active) {}
record WebhookView(UUID uid, String name, String url, List<String> eventTypes, boolean active, String secret /* only on create, else null */, Instant createdAt) {}
record DeliveryView(UUID uid, String eventType, UUID eventId, DeliveryStatus status, Integer responseStatus, int attempts, String lastError, Instant createdAt, Instant deliveredAt) {}
interface Webhooks {
  List<String> eventTypes();   // sale.completed, sale.returned, goods.received, stock.adjusted, stock.low, product.changed, tenant.registered
  List<WebhookView> list();
  WebhookView create(WebhookCommand c);       // https (or http://localhost) url; 32-byte random secret
  WebhookView change(UUID uid, WebhookCommand c);
  void delete(UUID uid);
  Page<DeliveryView> deliveries(UUID webhookUid, PageQuery query);
  /** Called by composition reactions: records a delivery per subscribed active webhook, then sends each as a
      CloudEvent (HMAC-SHA256 X-Pharmacy-Signature), up to 3 attempts with backoff, recording the outcome. */
  void dispatch(UUID eventId, String eventType, Instant occurredAt, Object data);
}
```
