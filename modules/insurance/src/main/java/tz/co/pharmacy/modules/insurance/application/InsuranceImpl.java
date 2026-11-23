package tz.co.pharmacy.modules.insurance.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import tz.co.pharmacy.modules.insurance.domain.Claim;
import tz.co.pharmacy.modules.insurance.domain.ClaimBatch;
import tz.co.pharmacy.modules.insurance.domain.ClaimLine;
import tz.co.pharmacy.modules.insurance.domain.CoverageCalculator;
import tz.co.pharmacy.modules.insurance.domain.FhirClaims;
import tz.co.pharmacy.modules.insurance.domain.Member;
import tz.co.pharmacy.modules.insurance.domain.Payer;
import tz.co.pharmacy.modules.insurance.domain.Remittance;
import tz.co.pharmacy.modules.insurance.domain.Scheme;
import tz.co.pharmacy.modules.insurance.events.ClaimAdjudicated;
import tz.co.pharmacy.modules.insurance.events.ClaimReduced;
import tz.co.pharmacy.modules.insurance.events.RemittanceReceived;
import tz.co.pharmacy.modules.insurance.persistence.ClaimBatchRepository;
import tz.co.pharmacy.modules.insurance.persistence.ClaimRepository;
import tz.co.pharmacy.modules.insurance.persistence.MemberRepository;
import tz.co.pharmacy.modules.insurance.persistence.PayerRepository;
import tz.co.pharmacy.modules.insurance.persistence.RemittanceRepository;
import tz.co.pharmacy.modules.insurance.persistence.SchemeRepository;
import tz.co.pharmacy.modules.insurance.ports.inbound.AdjudicationCommand;
import tz.co.pharmacy.modules.insurance.ports.inbound.ClaimBatchView;
import tz.co.pharmacy.modules.insurance.ports.inbound.ClaimCommand;
import tz.co.pharmacy.modules.insurance.ports.inbound.ClaimLineView;
import tz.co.pharmacy.modules.insurance.ports.inbound.ClaimStatus;
import tz.co.pharmacy.modules.insurance.ports.inbound.ClaimTotals;
import tz.co.pharmacy.modules.insurance.ports.inbound.ClaimView;
import tz.co.pharmacy.modules.insurance.ports.inbound.CopayType;
import tz.co.pharmacy.modules.insurance.ports.inbound.CoverageLine;
import tz.co.pharmacy.modules.insurance.ports.inbound.CoverageQuote;
import tz.co.pharmacy.modules.insurance.ports.inbound.Insurance;
import tz.co.pharmacy.modules.insurance.ports.inbound.MemberCommand;
import tz.co.pharmacy.modules.insurance.ports.inbound.MemberView;
import tz.co.pharmacy.modules.insurance.ports.inbound.PayerCommand;
import tz.co.pharmacy.modules.insurance.ports.inbound.PayerView;
import tz.co.pharmacy.modules.insurance.ports.inbound.RejectedTo;
import tz.co.pharmacy.modules.insurance.ports.inbound.RemittanceCommand;
import tz.co.pharmacy.modules.insurance.ports.inbound.RemittanceView;
import tz.co.pharmacy.modules.insurance.ports.inbound.SchemeCommand;
import tz.co.pharmacy.modules.insurance.ports.inbound.SchemeView;
import tz.co.pharmacy.modules.insurance.ports.outbound.InsuranceDirectory;
import tz.co.pharmacy.platform.bus.DomainEventPublisher;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.persistence.DocumentNumbers;
import tz.co.pharmacy.platform.persistence.Keyset;
import tz.co.pharmacy.platform.persistence.Specs;

/** Payers, schemes, members, the co-pay split, and the claim cycle from sale to remittance. */
@Service
class InsuranceImpl implements Insurance {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final PayerRepository payers;
  private final SchemeRepository schemes;
  private final MemberRepository members;
  private final ClaimRepository claims;
  private final ClaimBatchRepository batches;
  private final RemittanceRepository remittances;
  private final InsuranceDirectory directory;
  private final DocumentNumbers numbers;
  private final DomainEventPublisher events;
  private final Clock clock;
  private final JdbcTemplate jdbc;

  InsuranceImpl(
      PayerRepository payers,
      SchemeRepository schemes,
      MemberRepository members,
      ClaimRepository claims,
      ClaimBatchRepository batches,
      RemittanceRepository remittances,
      InsuranceDirectory directory,
      DocumentNumbers numbers,
      DomainEventPublisher events,
      Clock clock,
      DataSource dataSource) {
    this.payers = payers;
    this.schemes = schemes;
    this.members = members;
    this.claims = claims;
    this.batches = batches;
    this.remittances = remittances;
    this.directory = directory;
    this.numbers = numbers;
    this.events = events;
    this.clock = clock;
    this.jdbc = new JdbcTemplate(dataSource);
  }

  // ---------------------------------------------------------------- payers, schemes, members

  @Override
  @Transactional(readOnly = true)
  public List<PayerView> payers() {
    return payers.findAllByOrderByNameAsc().stream().map(InsuranceImpl::view).toList();
  }

  @Override
  @Transactional
  public PayerView createPayer(PayerCommand c) {
    String code = Checks.code(c.code(), "code");
    if (payers.findByCodeIgnoreCase(code).isPresent()) {
      throw Refusal.conflict("duplicate_code", "A payer with code " + code + " exists.");
    }
    Payer payer = new Payer();
    payer.setCode(code);
    apply(payer, c);
    return view(payers.save(payer));
  }

  @Override
  @Transactional
  public PayerView changePayer(UUID uid, PayerCommand c) {
    Payer payer = payer(uid);
    apply(payer, c);
    return view(payer);
  }

  private static void apply(Payer payer, PayerCommand c) {
    payer.setName(Checks.text(c.name(), "name", 200));
    payer.setContact(Checks.optionalText(c.contact(), "contact", 200));
    payer.setEmail(
        c.email() == null || c.email().isBlank() ? null : Checks.email(c.email(), "email"));
    int terms = c.paymentTermsDays() == null ? 30 : c.paymentTermsDays();
    if (terms < 0 || terms > 365) {
      throw Refusal.invalid(
          "invalid_payment_terms_days", "paymentTermsDays must be 0 to 365.", "paymentTermsDays");
    }
    payer.setPaymentTermsDays(terms);
    payer.setRequiresPreauth(Boolean.TRUE.equals(c.requiresPreauth()));
    payer.setActive(c.active() == null || c.active());
  }

  @Override
  @Transactional(readOnly = true)
  public List<SchemeView> schemes(UUID payerUid) {
    Map<UUID, Payer> byUid = payersByUid();
    List<Scheme> rows =
        payerUid == null
            ? schemes.findAllByOrderByNameAsc()
            : schemes.findByPayerUidOrderByNameAsc(payerUid);
    return rows.stream().map(s -> view(s, byUid.get(s.getPayerUid()))).toList();
  }

  @Override
  @Transactional
  public SchemeView createScheme(SchemeCommand c) {
    Scheme scheme = new Scheme();
    Payer payer = payer(Checks.required(c.payerUid(), "payerUid"));
    scheme.setPayerUid(payer.getUid());
    apply(scheme, c);
    return view(schemes.save(scheme), payer);
  }

  @Override
  @Transactional
  public SchemeView changeScheme(UUID uid, SchemeCommand c) {
    Scheme scheme = scheme(uid);
    apply(scheme, c);
    return view(scheme, payer(scheme.getPayerUid()));
  }

  private static void apply(Scheme scheme, SchemeCommand c) {
    scheme.setName(Checks.text(c.name(), "name", 200));
    CopayType type = c.copayType() == null ? CopayType.NONE : c.copayType();
    BigDecimal value = c.copayValue() == null ? BigDecimal.ZERO : c.copayValue();
    if (type == CopayType.PERCENT) {
      value = Checks.percent(value, "copayValue");
    } else {
      value = Checks.notNegative(value, "copayValue");
    }
    scheme.setCopayType(type);
    scheme.setCopayValue(value);
    scheme.setAnnualLimit(
        c.annualLimit() == null || c.annualLimit().signum() == 0
            ? null
            : Checks.notNegative(c.annualLimit(), "annualLimit"));
    scheme.setExcludedProductTypes(join(c.excludedProductTypes()));
    scheme.setExcludedProductUids(
        join(
            c.excludedProductUids() == null
                ? null
                : c.excludedProductUids().stream().map(UUID::toString).toList()));
    scheme.setActive(c.active() == null || c.active());
  }

  @Override
  @Transactional(readOnly = true)
  public Page<MemberView> members(String q, PageQuery query) {
    return Keyset.page(
        members,
        Specs.<Member>containsAny(q, "memberNumber", "customerName", "principalName"),
        query,
        this::view);
  }

  @Override
  @Transactional(readOnly = true)
  public List<MemberView> membersOf(UUID customerUid) {
    return members.findByCustomerUidOrderByIdDesc(customerUid).stream().map(this::view).toList();
  }

  @Override
  @Transactional
  public MemberView enrol(MemberCommand c) {
    Member member = new Member();
    apply(member, c);
    return view(members.save(member));
  }

  @Override
  @Transactional
  public MemberView changeMember(UUID uid, MemberCommand c) {
    Member member =
        members
            .findByUid(uid)
            .orElseThrow(() -> Refusal.notFound("member_not_found", "No such member."));
    apply(member, c);
    return view(member);
  }

  private void apply(Member member, MemberCommand c) {
    UUID customer = Checks.required(c.customerUid(), "customerUid");
    String name =
        directory
            .customerName(customer)
            .orElseThrow(
                () -> Refusal.invalid("customer_not_found", "No such customer.", "customerUid"));
    Scheme scheme = scheme(Checks.required(c.schemeUid(), "schemeUid"));
    String number = Checks.text(c.memberNumber(), "memberNumber", 60);
    members
        .findBySchemeUidAndMemberNumberIgnoreCase(scheme.getUid(), number)
        .filter(other -> !other.getUid().equals(member.getUid()))
        .ifPresent(
            other -> {
              throw Refusal.conflict(
                  "duplicate_member_number",
                  "Member number " + number + " is already enrolled in this scheme.");
            });
    if (c.validFrom() != null && c.validUntil() != null && c.validUntil().isBefore(c.validFrom())) {
      throw Refusal.invalid("invalid_valid_until", "validUntil is before validFrom.", "validUntil");
    }
    member.setCustomerUid(customer);
    member.setCustomerName(name);
    member.setSchemeUid(scheme.getUid());
    member.setMemberNumber(number);
    member.setPrincipalName(Checks.optionalText(c.principalName(), "principalName", 200));
    member.setValidFrom(c.validFrom());
    member.setValidUntil(c.validUntil());
    member.setActive(c.active() == null || c.active());
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<MemberView> memberOf(UUID customerUid, LocalDate on) {
    LocalDate day = on == null ? today() : on;
    return members.findByCustomerUidOrderByIdDesc(customerUid).stream()
        .filter(m -> m.coversOn(day))
        .filter(m -> schemes.findByUid(m.getSchemeUid()).map(Scheme::isActive).orElse(false))
        .findFirst()
        .map(this::view);
  }

  // ---------------------------------------------------------------- quote and claims

  @Override
  @Transactional(readOnly = true)
  public CoverageQuote quote(UUID memberUid, List<CoverageLine> lines, String preauthNumber) {
    Member member =
        members
            .findByUid(Checks.required(memberUid, "memberUid"))
            .orElseThrow(() -> Refusal.invalid("member_not_found", "No such member.", "memberUid"));
    Scheme scheme = scheme(member.getSchemeUid());
    Payer payer = payer(scheme.getPayerUid());
    if (!member.coversOn(today()) || !scheme.isActive() || !payer.isActive()) {
      throw Refusal.invalid(
          "member_inactive",
          "Membership " + member.getMemberNumber() + " is not active today.",
          "memberUid");
    }
    if (payer.isRequiresPreauth() && (preauthNumber == null || preauthNumber.isBlank())) {
      throw Refusal.invalid(
          "preauth_required",
          payer.getName() + " needs a pre-authorisation number.",
          "preauthNumber");
    }
    return CoverageCalculator.quote(
        member.getUid(),
        scheme.getCopayType(),
        scheme.getCopayValue(),
        scheme.getAnnualLimit(),
        usedThisYear(member.getUid()),
        split(scheme.getExcludedProductTypes()),
        split(scheme.getExcludedProductUids()).stream()
            .map(UUID::fromString)
            .collect(Collectors.toSet()),
        lines == null ? List.of() : lines);
  }

  @Override
  @Transactional
  public ClaimView createClaim(ClaimCommand c) {
    Member member =
        members
            .findByUid(Checks.required(c.memberUid(), "memberUid"))
            .orElseThrow(() -> Refusal.invalid("member_not_found", "No such member.", "memberUid"));
    Scheme scheme = scheme(member.getSchemeUid());
    Claim claim = new Claim();
    claim.setNumber(numbers.format("CLM", 6));
    claim.setPayerUid(scheme.getPayerUid());
    claim.setMemberUid(member.getUid());
    claim.setCustomerUid(member.getCustomerUid());
    claim.setMemberNumber(member.getMemberNumber());
    claim.setPatientName(c.patientName() == null ? member.getCustomerName() : c.patientName());
    claim.setSaleUid(c.saleUid());
    claim.setSaleNumber(c.saleNumber());
    claim.setPreauthNumber(Checks.optionalText(c.preauthNumber(), "preauthNumber", 60));
    claim.setDiagnosisCode(diagnosis(c.diagnosisCode()));
    for (ClaimLineView line : Checks.notEmpty(c.lines(), "lines")) {
      ClaimLine row = new ClaimLine();
      row.setProductUid(line.productUid());
      row.setProductName(line.productName());
      row.setQuantity(line.quantity());
      row.setAmount(Amounts.money(line.amount()));
      claim.add(row);
    }
    return view(claims.save(claim));
  }

  @Override
  @Transactional(readOnly = true)
  public Page<ClaimView> claims(UUID payerUid, ClaimStatus status, PageQuery query) {
    return Keyset.page(
        claims,
        Specs.<Claim>equal("payerUid", payerUid).and(Specs.equal("status", status)),
        query,
        this::view);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ClaimView> claim(UUID uid) {
    return claims.findByUid(uid).map(this::view);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ClaimView> claimForSale(UUID saleUid) {
    return claims.findFirstBySaleUidOrderByIdDesc(saleUid).map(this::view);
  }

  @Override
  @Transactional
  public Optional<ClaimView> reduceForReturn(
      UUID saleUid, BigDecimal amount, String reference, String refundMethod) {
    Optional<Claim> found = claims.findFirstBySaleUidOrderByIdDesc(saleUid);
    if (found.isEmpty() || amount == null || amount.signum() <= 0) {
      return found.map(this::view);
    }
    Claim claim = found.get();
    if (claim.getStatus() == ClaimStatus.VOIDED) {
      return Optional.of(view(claim));
    }
    BigDecimal value = Amounts.money(amount.min(claim.getClaimed()));
    // Where the share comes off, so that the books move only where the claim's value still sits:
    // an undecided claim is all receivable; a decided one owes approved − paid, and its rejected
    // part is held as the patient's charge or a write-off (MON-5).
    BigDecimal receivable = value;
    BigDecimal patient = Amounts.ZERO;
    BigDecimal writeOff = Amounts.ZERO;
    if (claim.getStatus() == ClaimStatus.DRAFT && value.compareTo(claim.getClaimed()) >= 0) {
      claim.setStatus(ClaimStatus.VOIDED);
      claim.setNote("Voided: sale fully returned (" + reference + ")");
    } else {
      ClaimLine line = new ClaimLine();
      line.setProductName("Return " + reference);
      line.setQuantity(0);
      line.setAmount(value.negate());
      claim.add(line);
      if (claim.getApproved() != null) {
        BigDecimal due = claim.due().max(BigDecimal.ZERO);
        BigDecimal left = value.subtract(value.min(due));
        patient = Amounts.money(left.min(claim.getChargedToPatient()));
        left = left.subtract(patient);
        writeOff = Amounts.money(left.min(claim.getWrittenOff()));
        // What remains was already paid by the payer: the claim owes it back (approved < paid),
        // which is what the receivable's credit balance shows.
        receivable = Amounts.money(value.subtract(patient).subtract(writeOff));
        claim.setApproved(Amounts.money(claim.getApproved().subtract(receivable)));
        claim.setChargedToPatient(Amounts.money(claim.getChargedToPatient().subtract(patient)));
        claim.setWrittenOff(Amounts.money(claim.getWrittenOff().subtract(writeOff)));
      }
    }
    BigDecimal fromReceivable = receivable;
    BigDecimal fromPatient = patient;
    BigDecimal fromWriteOff = writeOff;
    events.publish(
        "insurance.claim_reduced",
        meta ->
            new ClaimReduced(
                meta,
                claim.getUid(),
                saleUid,
                value,
                refundMethod,
                reference,
                fromReceivable,
                fromWriteOff,
                fromPatient,
                claim.getCustomerUid()));
    return Optional.of(view(claim));
  }

  // ---------------------------------------------------------------- batches

  @Override
  @Transactional
  public ClaimBatchView batch(UUID payerUid, LocalDate from, LocalDate to) {
    Payer payer = payer(Checks.required(payerUid, "payerUid"));
    LocalDate start = Checks.required(from, "from");
    LocalDate end = Checks.required(to, "to");
    if (end.isBefore(start)) {
      throw Refusal.invalid("invalid_range", "to is before from.", "to");
    }
    List<Claim> drafts =
        claims.drafts(
            payer.getUid(),
            start.atStartOfDay().toInstant(ZoneOffset.UTC),
            end.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));
    if (drafts.isEmpty()) {
      throw Refusal.invalid(
          "no_claims_in_period",
          "No draft claims to " + payer.getName() + " in that period.",
          "from");
    }
    ClaimBatch batch = new ClaimBatch();
    batch.setNumber(numbers.format("BAT", 6));
    batch.setPayerUid(payer.getUid());
    batch.setPeriodFrom(start);
    batch.setPeriodTo(end);
    batch.setSubmittedAt(Instant.now(clock));
    ClaimBatch saved = batches.save(batch);
    for (Claim claim : drafts) {
      claim.setBatchUid(saved.getUid());
      claim.setStatus(ClaimStatus.SUBMITTED);
    }
    return view(saved, payer, drafts);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<ClaimBatchView> batches(UUID payerUid, PageQuery query) {
    Map<UUID, Payer> byUid = payersByUid();
    return Keyset.page(
        batches,
        Specs.<ClaimBatch>equal("payerUid", payerUid),
        query,
        b -> view(b, byUid.get(b.getPayerUid()), claims.findByBatchUidOrderByIdAsc(b.getUid())));
  }

  @Override
  @Transactional(readOnly = true)
  public byte[] exportBatch(UUID batchUid, String format) {
    ClaimBatch batch =
        batches
            .findByUid(batchUid)
            .orElseThrow(() -> Refusal.notFound("batch_not_found", "No such batch."));
    Payer payer = payer(batch.getPayerUid());
    List<Claim> rows = claims.findByBatchUidOrderByIdAsc(batch.getUid());
    String kind = format == null ? "CSV" : format.trim().toUpperCase();
    return switch (kind) {
      case "JSON", "FHIR" ->
          JSON.writerWithDefaultPrettyPrinter()
              .writeValueAsBytes(
                  FhirClaims.bundle(
                      batch.getNumber(),
                      payer.getName(),
                      directory.providerName(),
                      directory.currency(),
                      rows));
      case "CSV" -> csv(rows).getBytes(StandardCharsets.UTF_8);
      default -> throw Refusal.invalid("invalid_format", "format is CSV or JSON.", "format");
    };
  }

  static String csv(List<Claim> rows) {
    StringBuilder out =
        new StringBuilder(
            "claim_number,sale_number,date,member_number,patient,preauth,diagnosis,item,quantity,amount\n");
    for (Claim claim : rows) {
      for (ClaimLine line : claim.getLines()) {
        out.append(
                String.join(
                    ",",
                    cell(claim.getNumber()),
                    cell(claim.getSaleNumber()),
                    cell(
                        claim.getCreatedAt() == null
                            ? ""
                            : claim
                                .getCreatedAt()
                                .atOffset(ZoneOffset.UTC)
                                .toLocalDate()
                                .toString()),
                    cell(claim.getMemberNumber()),
                    cell(claim.getPatientName()),
                    cell(claim.getPreauthNumber()),
                    cell(claim.getDiagnosisCode()),
                    cell(line.getProductName()),
                    String.valueOf(line.getQuantity()),
                    Amounts.money(line.getAmount()).toPlainString()))
            .append('\n');
      }
    }
    return out.toString();
  }

  private static String cell(String value) {
    String text = value == null ? "" : value;
    return text.contains(",") || text.contains("\"") || text.contains("\n")
        ? "\"" + text.replace("\"", "\"\"") + "\""
        : text;
  }

  // ---------------------------------------------------------------- adjudication and remittance

  @Override
  @Transactional
  public ClaimView adjudicate(UUID claimUid, AdjudicationCommand c) {
    Claim claim =
        claims
            .findByUid(claimUid)
            .orElseThrow(() -> Refusal.notFound("claim_not_found", "No such claim."));
    if (claim.getStatus() == ClaimStatus.VOIDED || claim.getStatus() == ClaimStatus.PAID) {
      throw Refusal.invalid(
          "claim_closed",
          "Claim " + claim.getNumber() + " is " + claim.getStatus() + ".",
          "status");
    }
    Map<UUID, ClaimLineView> decided = new HashMap<>();
    if (c.lines() != null) {
      for (ClaimLineView line : c.lines()) {
        if (line.productUid() != null) {
          decided.put(line.productUid(), line);
        }
      }
    }
    BigDecimal approved = BigDecimal.ZERO;
    for (ClaimLine line : claim.getLines()) {
      BigDecimal lineApproved = line.getAmount();
      ClaimLineView decision =
          line.getProductUid() == null ? null : decided.get(line.getProductUid());
      if (c.status() == ClaimStatus.REJECTED) {
        lineApproved = line.getAmount().signum() > 0 ? BigDecimal.ZERO : line.getAmount();
      } else if (decision != null && decision.approvedAmount() != null) {
        lineApproved =
            decision
                .approvedAmount()
                .min(line.getAmount().max(BigDecimal.ZERO))
                .max(BigDecimal.ZERO);
      }
      line.setApprovedAmount(Amounts.money(lineApproved));
      line.setRejectionReason(
          decision == null
              ? null
              : Checks.optionalText(decision.rejectionReason(), "rejectionReason", 300));
      approved = approved.add(lineApproved);
    }
    approved = Amounts.money(approved.max(BigDecimal.ZERO));
    BigDecimal claimed = claim.getClaimed();
    BigDecimal rejected = Amounts.money(claimed.subtract(approved).max(BigDecimal.ZERO));
    ClaimStatus status =
        approved.signum() == 0
            ? ClaimStatus.REJECTED
            : rejected.signum() == 0 ? ClaimStatus.APPROVED : ClaimStatus.PARTIALLY_APPROVED;
    claim.setApproved(approved);
    claim.setStatus(status);
    claim.setNote(Checks.optionalText(c.note(), "note", 500));
    RejectedTo to = c.rejectedTo() == null ? RejectedTo.WRITE_OFF : c.rejectedTo();
    // MON-4: the books already hold the earlier decision's rejection; post only the move from it
    // to this one (either sign), also when the rejected part changes from write-off to patient.
    boolean toPatient = to == RejectedTo.PATIENT_ACCOUNT && claim.getCustomerUid() != null;
    BigDecimal writeOffChange =
        Amounts.money((toPatient ? Amounts.ZERO : rejected).subtract(claim.getWrittenOff()));
    BigDecimal patientChange =
        Amounts.money((toPatient ? rejected : Amounts.ZERO).subtract(claim.getChargedToPatient()));
    claim.setWrittenOff(toPatient ? Amounts.ZERO : rejected);
    claim.setChargedToPatient(toPatient ? rejected : Amounts.ZERO);
    BigDecimal approvedTotal = approved;
    events.publish(
        "insurance.claim_adjudicated",
        meta ->
            new ClaimAdjudicated(
                meta,
                claim.getUid(),
                claim.getPayerUid(),
                claimed,
                approvedTotal,
                rejected,
                to.name(),
                claim.getCustomerUid(),
                claim.getSaleUid(),
                claim.getSaleNumber(),
                claim.getNumber(),
                writeOffChange,
                patientChange));
    return view(claim);
  }

  @Override
  @Transactional
  public List<ClaimView> remit(RemittanceCommand c) {
    Payer payer = payer(Checks.required(c.payerUid(), "payerUid"));
    BigDecimal amount = Checks.notNegative(c.amount(), "amount");
    if (amount.signum() == 0) {
      throw Refusal.invalid("invalid_amount", "amount must be more than zero.", "amount");
    }
    List<Claim> payable = claims.payable(payer.getUid());
    Map<UUID, Claim> byUid =
        payable.stream().collect(Collectors.toMap(Claim::getUid, Function.identity()));
    Map<String, BigDecimal> allocated = new LinkedHashMap<>();
    BigDecimal left = amount;
    if (c.perClaim() != null && !c.perClaim().isEmpty()) {
      // MON-19: a negative or zero share would raise what the payer owes on one claim to pay
      // another with it; every share is checked before any is paid.
      for (BigDecimal share : c.perClaim().values()) {
        if (share == null || Amounts.money(share).signum() <= 0) {
          throw Refusal.invalid(
              "invalid_amount", "Each perClaim amount must be more than zero.", "perClaim");
        }
      }
      for (var entry : c.perClaim().entrySet()) {
        Claim claim = byUid.get(entry.getKey());
        if (claim == null) {
          throw Refusal.invalid(
              "claim_not_payable", "That claim is not awaiting payment.", "perClaim");
        }
        BigDecimal value = Amounts.money(entry.getValue());
        if (value.compareTo(claim.due()) > 0) {
          throw Refusal.invalid(
              "remittance_exceeds_due",
              claim.getNumber() + " has only " + claim.due().toPlainString() + " due.",
              "perClaim");
        }
        pay(claim, value, allocated);
        left = left.subtract(value);
      }
      if (left.signum() != 0) {
        throw Refusal.invalid("remittance_mismatch", "perClaim must add up to amount.", "perClaim");
      }
    } else {
      BigDecimal due = payable.stream().map(Claim::due).reduce(BigDecimal.ZERO, BigDecimal::add);
      if (amount.compareTo(due) > 0) {
        throw Refusal.invalid(
            "remittance_exceeds_due",
            payer.getName() + " owes " + due.toPlainString() + " on approved claims.",
            "amount");
      }
      for (Claim claim : payable) {
        if (left.signum() <= 0) {
          break;
        }
        BigDecimal value = Amounts.money(left.min(claim.due()));
        if (value.signum() > 0) {
          pay(claim, value, allocated);
          left = left.subtract(value);
        }
      }
    }
    Remittance remittance = new Remittance();
    remittance.setNumber(numbers.format("REM", 6));
    remittance.setPayerUid(payer.getUid());
    remittance.setAmount(amount);
    remittance.setReference(Checks.optionalText(c.reference(), "reference", 100));
    remittance.setRemittedOn(c.date() == null ? today() : c.date());
    remittance.setAllocations(
        allocated.entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue().toPlainString())
            .collect(Collectors.joining(";")));
    Remittance saved = remittances.save(remittance);
    events.publish(
        "insurance.remittance_received",
        meta ->
            new RemittanceReceived(
                meta,
                payer.getUid(),
                amount,
                saved.getReference(),
                saved.getUid(),
                saved.getNumber(),
                saved.getRemittedOn()));
    return payable.stream()
        .filter(claim -> allocated.containsKey(claim.getNumber()))
        .map(this::view)
        .toList();
  }

  private static void pay(Claim claim, BigDecimal value, Map<String, BigDecimal> allocated) {
    claim.setPaid(Amounts.money(claim.getPaid().add(value)));
    if (claim.due().signum() <= 0) {
      claim.setStatus(ClaimStatus.PAID);
    }
    allocated.merge(claim.getNumber(), value, BigDecimal::add);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<RemittanceView> remittances(UUID payerUid, PageQuery query) {
    Map<UUID, Payer> byUid = payersByUid();
    return Keyset.page(
        remittances,
        Specs.<Remittance>equal("payerUid", payerUid),
        query,
        r -> {
          Map<String, BigDecimal> allocations = new LinkedHashMap<>();
          for (String part : split(r.getAllocations())) {
            String[] kv = part.split("=");
            if (kv.length == 2) {
              allocations.put(kv[0], new BigDecimal(kv[1]));
            }
          }
          Payer payer = byUid.get(r.getPayerUid());
          return new RemittanceView(
              r.getUid(),
              r.getNumber(),
              r.getPayerUid(),
              payer == null ? null : payer.getName(),
              r.getAmount(),
              r.getReference(),
              r.getRemittedOn(),
              allocations,
              r.getCreatedAt());
        });
  }

  @Override
  @Transactional(readOnly = true)
  public ClaimTotals totals() {
    return jdbc.queryForObject(
        "select count(*) filter (where status in ('DRAFT','SUBMITTED','APPROVED','PARTIALLY_APPROVED')) as pending,"
            + " coalesce(sum(case when status in ('DRAFT','SUBMITTED') then claimed"
            + "   when status in ('APPROVED','PARTIALLY_APPROVED') then approved - paid end), 0) as pending_amount,"
            + " count(*) filter (where status = 'DRAFT') as drafts,"
            + " coalesce(sum(case when status in ('APPROVED','PARTIALLY_APPROVED') then approved - paid end), 0) as approved_unpaid"
            + " from insurance.claims",
        (row, i) ->
            new ClaimTotals(
                row.getLong("pending"),
                Amounts.money(row.getBigDecimal("pending_amount")),
                row.getLong("drafts"),
                Amounts.money(row.getBigDecimal("approved_unpaid"))));
  }

  // ---------------------------------------------------------------- helpers

  /**
   * What the member's scheme has taken on this calendar year: approved where decided, else claimed.
   */
  private BigDecimal usedThisYear(UUID memberUid) {
    int year = today().getYear();
    BigDecimal used =
        jdbc.queryForObject(
            "select coalesce(sum(case when status in ('APPROVED','PARTIALLY_APPROVED','PAID') then approved"
                + " else claimed end), 0) from insurance.claims"
                + " where member_uid = ? and status not in ('REJECTED','VOIDED')"
                + " and extract(year from created_at) = ?",
            BigDecimal.class,
            memberUid,
            year);
    return used == null ? BigDecimal.ZERO : used;
  }

  private static String diagnosis(String code) {
    String value = Checks.optionalText(code, "diagnosisCode", 10);
    if (value == null) {
      return null;
    }
    String upper = value.toUpperCase().replace(" ", "");
    if (!upper.matches("^[A-Z][0-9]{2}(\\.?[0-9A-Z]{1,4})?$")) {
      throw Refusal.invalid(
          "invalid_diagnosis_code",
          "diagnosisCode must be an ICD-10 code such as J06.9.",
          "diagnosisCode");
    }
    return upper;
  }

  private Payer payer(UUID uid) {
    return payers
        .findByUid(uid)
        .orElseThrow(() -> Refusal.invalid("payer_not_found", "No such payer.", "payerUid"));
  }

  private Scheme scheme(UUID uid) {
    return schemes
        .findByUid(uid)
        .orElseThrow(() -> Refusal.invalid("scheme_not_found", "No such scheme.", "schemeUid"));
  }

  private Map<UUID, Payer> payersByUid() {
    return payers.findAll().stream().collect(Collectors.toMap(Payer::getUid, Function.identity()));
  }

  private static String join(java.util.Collection<String> values) {
    if (values == null) {
      return "";
    }
    return values.stream()
        .filter(v -> v != null && !v.isBlank())
        .map(String::trim)
        .distinct()
        .collect(Collectors.joining(","));
  }

  private static Set<String> split(String joined) {
    if (joined == null || joined.isBlank()) {
      return Set.of();
    }
    return new HashSet<>(Arrays.asList(joined.split("[,;]")));
  }

  private LocalDate today() {
    return LocalDate.now(clock);
  }

  private static PayerView view(Payer p) {
    return new PayerView(
        p.getUid(),
        p.getCode(),
        p.getName(),
        p.getContact(),
        p.getEmail(),
        p.getPaymentTermsDays(),
        p.isRequiresPreauth(),
        p.isActive());
  }

  private static SchemeView view(Scheme s, Payer payer) {
    return new SchemeView(
        s.getUid(),
        s.getPayerUid(),
        payer == null ? null : payer.getName(),
        s.getName(),
        s.getCopayType(),
        s.getCopayValue(),
        s.getAnnualLimit(),
        split(s.getExcludedProductTypes()),
        split(s.getExcludedProductUids()).stream()
            .map(UUID::fromString)
            .collect(Collectors.toSet()),
        s.isActive());
  }

  private MemberView view(Member m) {
    Scheme scheme = schemes.findByUid(m.getSchemeUid()).orElse(null);
    Payer payer = scheme == null ? null : payers.findByUid(scheme.getPayerUid()).orElse(null);
    return new MemberView(
        m.getUid(),
        m.getCustomerUid(),
        m.getCustomerName(),
        m.getSchemeUid(),
        scheme == null ? null : scheme.getName(),
        payer == null ? null : payer.getName(),
        m.getMemberNumber(),
        m.getPrincipalName(),
        m.getValidFrom(),
        m.getValidUntil(),
        Amounts.money(usedThisYear(m.getUid())),
        m.isActive(),
        payer == null ? null : payer.getUid(),
        payer != null && payer.isRequiresPreauth(),
        scheme == null ? CopayType.NONE : scheme.getCopayType(),
        scheme == null ? BigDecimal.ZERO : scheme.getCopayValue(),
        scheme == null ? null : scheme.getAnnualLimit());
  }

  private ClaimView view(Claim c) {
    Payer payer = payers.findByUid(c.getPayerUid()).orElse(null);
    String batchNumber =
        c.getBatchUid() == null
            ? null
            : batches.findByUid(c.getBatchUid()).map(ClaimBatch::getNumber).orElse(null);
    List<ClaimLineView> lines = new ArrayList<>();
    for (ClaimLine line : c.getLines()) {
      lines.add(
          new ClaimLineView(
              line.getProductUid(),
              line.getProductName(),
              line.getQuantity(),
              line.getAmount(),
              line.getApprovedAmount(),
              line.getRejectionReason()));
    }
    return new ClaimView(
        c.getUid(),
        c.getNumber(),
        c.getPayerUid(),
        payer == null ? null : payer.getName(),
        c.getMemberUid(),
        c.getMemberNumber(),
        c.getPatientName(),
        c.getSaleUid(),
        c.getSaleNumber(),
        c.getPreauthNumber(),
        c.getDiagnosisCode(),
        c.getClaimed(),
        c.getApproved(),
        c.getPaid(),
        c.getStatus(),
        c.getBatchUid(),
        lines,
        c.getCreatedAt(),
        c.getCustomerUid(),
        batchNumber,
        c.getNote());
  }

  private static ClaimBatchView view(ClaimBatch b, Payer payer, List<Claim> rows) {
    BigDecimal claimed =
        rows.stream().map(Claim::getClaimed).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal approved =
        rows.stream()
            .map(c -> c.getApproved() == null ? BigDecimal.ZERO : c.getApproved())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal paid = rows.stream().map(Claim::getPaid).reduce(BigDecimal.ZERO, BigDecimal::add);
    boolean allPaid =
        !rows.isEmpty()
            && rows.stream()
                .allMatch(
                    c ->
                        c.getStatus() == ClaimStatus.PAID || c.getStatus() == ClaimStatus.REJECTED);
    boolean anyDecided = rows.stream().anyMatch(c -> c.getApproved() != null);
    return new ClaimBatchView(
        b.getUid(),
        b.getNumber(),
        b.getPayerUid(),
        payer == null ? null : payer.getName(),
        b.getPeriodFrom(),
        b.getPeriodTo(),
        rows.size(),
        Amounts.money(claimed),
        Amounts.money(approved),
        Amounts.money(paid),
        allPaid ? "SETTLED" : anyDecided ? "ADJUDICATING" : b.getStatus(),
        b.getSubmittedAt());
  }
}
