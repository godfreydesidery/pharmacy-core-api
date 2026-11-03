# Module ports, part 3: receivables and insurance (credit and insured sales)

Binding, like parts 1 and 2. All the "Shared rules for this wave" in `MODULE-PORTS-2.md` apply.

The Maven modules `modules/receivables` and `modules/insurance` already exist. They are wired into delivery, pharmacy-core and **application/checkout**, and their schemas are in `ModuleMigrations`.

## receivables (`tz.co.pharmacy.modules.receivables`)

Migration `V1__receivables_schema.sql`, with RLS.

```java
enum EntryType { CHARGE, PAYMENT, CREDIT_NOTE, WRITE_OFF, ADJUSTMENT }
record AccountView(UUID customerUid, String customerName, BigDecimal creditLimit, BigDecimal balance, BigDecimal available,
                   BigDecimal overdue, LocalDate oldestOpenDate, boolean onHold) {}
record EntryView(UUID uid, EntryType type, String reference, UUID saleUid, BigDecimal amount, BigDecimal openAmount,
                 LocalDate date, LocalDate dueDate, String note, Instant createdAt, String createdBy) {}
record ReceivePaymentCommand(UUID customerUid, BigDecimal amount, String method, String reference, LocalDate date,
                             List<UUID> allocateTo /* charge uids; empty = oldest first */) {}
record AgeingRow(UUID customerUid, String customerName, BigDecimal current, BigDecimal d31to60, BigDecimal d61to90,
                 BigDecimal over90, BigDecimal total) {}
interface CustomerCredit {
  AccountView account(UUID customerUid);   // created on first use; credit limit + terms from partners via ports.outbound.CustomerDirectory
  /** 422 credit_limit_exceeded (balance + amount > limit) or 422 account_on_hold. Checkout calls it for a CREDIT payment. */
  void checkCredit(UUID customerUid, BigDecimal amount);
  EntryView charge(UUID customerUid, UUID saleUid, String saleNumber, BigDecimal amount, LocalDate date);  // due = date + terms days
  EntryView receivePayment(ReceivePaymentCommand c);   // allocates FIFO or as given; publishes receivable.payment_received
  EntryView creditNote(UUID customerUid, UUID saleUid, String reference, BigDecimal amount);   // on a return of a credit sale
  EntryView writeOff(UUID customerUid, BigDecimal amount, String reason);
  void hold(UUID customerUid, boolean onHold, String reason);
  Page<EntryView> statement(UUID customerUid, LocalDate from, LocalDate to, PageQuery query);
  List<AgeingRow> ageing(LocalDate asOf);
  Page<AccountView> accounts(String q, boolean withBalanceOnly, PageQuery query);
}
// events
record PaymentReceived(EventMetadata metadata, UUID customerUid, BigDecimal amount, String method, String reference)
    implements DomainEvent {}   // "receivable.payment_received"
```

## insurance (`tz.co.pharmacy.modules.insurance`)

Migration `V1__insurance_schema.sql`, with RLS.

```java
enum CopayType { NONE, PERCENT, FIXED }
enum ClaimStatus { DRAFT, SUBMITTED, APPROVED, PARTIALLY_APPROVED, REJECTED, PAID }
record PayerView(UUID uid, String code, String name, String contact, String email, int paymentTermsDays, boolean requiresPreauth, boolean active) {}
record PayerCommand(String code, String name, String contact, String email, Integer paymentTermsDays, Boolean requiresPreauth, Boolean active) {}
record SchemeView(UUID uid, UUID payerUid, String payerName, String name, CopayType copayType, BigDecimal copayValue,
                  BigDecimal annualLimit, Set<String> excludedProductTypes, Set<UUID> excludedProductUids, boolean active) {}
record SchemeCommand(UUID payerUid, String name, CopayType copayType, BigDecimal copayValue, BigDecimal annualLimit,
                     Set<String> excludedProductTypes, Set<UUID> excludedProductUids, Boolean active) {}
record MemberView(UUID uid, UUID customerUid, String customerName, UUID schemeUid, String schemeName, String payerName,
                  String memberNumber, String principalName, LocalDate validFrom, LocalDate validUntil, BigDecimal usedThisYear, boolean active) {}
record MemberCommand(UUID customerUid, UUID schemeUid, String memberNumber, String principalName, LocalDate validFrom, LocalDate validUntil) {}
record CoverageLine(UUID productUid, String productType, BigDecimal lineTotal) {}
record CoverageQuote(UUID memberUid, BigDecimal covered, BigDecimal copay, BigDecimal notCovered, List<String> notes) {}
record ClaimLineView(UUID productUid, String productName, int quantity, BigDecimal amount, BigDecimal approvedAmount, String rejectionReason) {}
record ClaimView(UUID uid, String number, UUID payerUid, String payerName, UUID memberUid, String memberNumber, String patientName,
                 UUID saleUid, String saleNumber, String preauthNumber, String diagnosisCode /* ICD-10 */, BigDecimal claimed,
                 BigDecimal approved, BigDecimal paid, ClaimStatus status, UUID batchUid, List<ClaimLineView> lines, Instant createdAt) {}
record ClaimBatchView(UUID uid, String number, UUID payerUid, String payerName, LocalDate periodFrom, LocalDate periodTo,
                      int claimCount, BigDecimal claimed, BigDecimal approved, BigDecimal paid, String status, Instant submittedAt) {}
record ClaimCommand(UUID memberUid, UUID saleUid, String saleNumber, String patientName, String preauthNumber, String diagnosisCode,
                    List<ClaimLineView> lines) {}
record AdjudicationCommand(ClaimStatus status, List<ClaimLineView> lines /* approvedAmount + rejectionReason per line */, String note) {}
record RemittanceCommand(UUID payerUid, BigDecimal amount, String reference, LocalDate date,
                         Map<UUID, BigDecimal> perClaim /* empty = oldest approved first */) {}
interface Insurance {
  List<PayerView> payers(); PayerView createPayer(PayerCommand c); PayerView changePayer(UUID uid, PayerCommand c);
  List<SchemeView> schemes(UUID payerUid); SchemeView createScheme(SchemeCommand c); SchemeView changeScheme(UUID uid, SchemeCommand c);
  Page<MemberView> members(String q, PageQuery query); MemberView enrol(MemberCommand c); MemberView changeMember(UUID uid, MemberCommand c);
  Optional<MemberView> memberOf(UUID customerUid, LocalDate on);
  /** Copay split for a basket: excluded items -> notCovered (patient pays), copay per scheme, annual limit.
      422 member_inactive / preauth_required. */
  CoverageQuote quote(UUID memberUid, List<CoverageLine> lines, String preauthNumber);
  ClaimView createClaim(ClaimCommand c);           // checkout calls it in the sale transaction; CLM-000001; DRAFT
  Page<ClaimView> claims(UUID payerUid, ClaimStatus status, PageQuery query); Optional<ClaimView> claim(UUID uid);
  ClaimBatchView batch(UUID payerUid, LocalDate from, LocalDate to);   // DRAFT claims -> batch, claims SUBMITTED; BAT-000001
  List<ClaimBatchView> batches(UUID payerUid);
  byte[] exportBatch(UUID batchUid, String format /* CSV | JSON = FHIR R4 Claim bundle */);
  ClaimView adjudicate(UUID claimUid, AdjudicationCommand c);   // publishes insurance.claim_adjudicated
  List<ClaimView> remit(RemittanceCommand c);                   // PAID / partial; publishes insurance.remittance_received
}
// events
record ClaimAdjudicated(EventMetadata metadata, UUID claimUid, UUID payerUid, BigDecimal claimed, BigDecimal approved, BigDecimal rejected) implements DomainEvent {}
record RemittanceReceived(EventMetadata metadata, UUID payerUid, BigDecimal amount, String reference) implements DomainEvent {}
```

## Checkout

`application/checkout` is also edited by the fx fork and the product-types fork, so use small edits only.

- **New command fields.** `CheckoutCommand` gains, appended last: `UUID memberUid`, `String preauthNumber`, `String diagnosisCode`.
- **Insured sale.** When a member is given:
  - call `insurance.quote(...)`;
  - the sale must carry an `INSURANCE` payment of exactly `covered`, otherwise refuse with 422 `insurance_amount_mismatch`;
  - the patient pays `copay + notCovered` by other methods;
  - after the sale is recorded, call `insurance.createClaim(...)`.
- **Credit sale.** For a `CREDIT` payment, call `customerCredit.checkCredit` before the sale and `customerCredit.charge` after it, in the same transaction.
- **Returns.**
  - Returning a credit sale issues a `creditNote`.
  - Returning an insured sale adds a negative line to its claim, or voids the claim while it is still DRAFT.
- **New route.** `POST /api/v1/pos/coverage-quote {memberUid, lines[{productUid, quantity}], preauthNumber}` returns the `CoverageQuote`.

## Finance (composition reactions; idempotent `Ledger` methods)

| Event | Debit | Credit |
|---|---|---|
| `receivable.payment_received` | Cash / Bank / Mobile Money | 1100 |
| `insurance.remittance_received` | 1010 | 1110 |
| `insurance.claim_adjudicated`, rejected amount > 0 | 6095 Insurance rejections (EXPENSE; add to the chart idempotently) | 1110 |

## Delivery

| Path | Permissions | Routes |
|---|---|---|
| `/api/v1/receivables` | `finance.view` / `finance.manage` | `/accounts`, `/accounts/{customerUid}`, `/accounts/{customerUid}/statement`, `POST /payments`, `POST /accounts/{customerUid}/hold`, `/ageing`, `POST /write-offs` |
| `/api/v1/insurance` (payers, schemes, members) | `partners.view` / `partners.manage` | `/payers`, `/schemes`, `/members` |
| `/api/v1/insurance` (claims, batches, remittances) | `finance.view` / `finance.manage` | `/claims`, `/claims/{uid}/adjudicate`, `/batches`, `/batches/{uid}/export?format=`, `/remittances` |

## Web

- **POS:**
  - picking a customer auto-detects their insurance membership;
  - a coverage panel shows covered, co-pay and not covered, and prefills the insurance payment line;
  - preauth and diagnosis fields appear when the scheme requires them;
  - a credit payment shows the available credit or the refusal.
- **Customer detail:** account balance, statement, receive payment, ageing badge, insurance memberships.
- **Finance:** a Receivables page with balances, the ageing report, and receive payment.
- **Insurance section:**
  - payers and schemes;
  - members;
  - claims, filtered by status, with an adjudicate dialog;
  - batches: create from a period, export to CSV or FHIR JSON;
  - remittances.
- **Dashboard:** "Receivables" and "Pending claims" KPIs (small edits).

## Demo seed (small edit)

- an NHIF payer with a scheme at 10 % co-pay;
- Neema Kileo enrolled as a member;
- City Clinic with a TZS 2,000,000 credit limit;
- one insured sale and one credit sale.

## Contract

Add "Receivables" and "Insurance" sections to `api-v1.md`.
