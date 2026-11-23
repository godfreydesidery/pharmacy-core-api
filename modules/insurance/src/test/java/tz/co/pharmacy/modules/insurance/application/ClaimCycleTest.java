package tz.co.pharmacy.modules.insurance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tz.co.pharmacy.modules.insurance.domain.Claim;
import tz.co.pharmacy.modules.insurance.domain.ClaimLine;
import tz.co.pharmacy.modules.insurance.domain.FhirClaims;
import tz.co.pharmacy.modules.insurance.domain.Payer;
import tz.co.pharmacy.modules.insurance.events.ClaimAdjudicated;
import tz.co.pharmacy.modules.insurance.events.ClaimReduced;
import tz.co.pharmacy.modules.insurance.persistence.ClaimBatchRepository;
import tz.co.pharmacy.modules.insurance.persistence.ClaimRepository;
import tz.co.pharmacy.modules.insurance.persistence.MemberRepository;
import tz.co.pharmacy.modules.insurance.persistence.PayerRepository;
import tz.co.pharmacy.modules.insurance.persistence.RemittanceRepository;
import tz.co.pharmacy.modules.insurance.persistence.SchemeRepository;
import tz.co.pharmacy.modules.insurance.ports.inbound.AdjudicationCommand;
import tz.co.pharmacy.modules.insurance.ports.inbound.ClaimLineView;
import tz.co.pharmacy.modules.insurance.ports.inbound.ClaimStatus;
import tz.co.pharmacy.modules.insurance.ports.inbound.ClaimView;
import tz.co.pharmacy.modules.insurance.ports.inbound.RejectedTo;
import tz.co.pharmacy.modules.insurance.ports.inbound.RemittanceCommand;
import tz.co.pharmacy.modules.insurance.ports.outbound.InsuranceDirectory;
import tz.co.pharmacy.platform.bus.DomainEventPublisher;
import tz.co.pharmacy.platform.kernel.EventMetadata;
import tz.co.pharmacy.platform.persistence.DocumentNumbers;

/** Scenario 11: partial approval, then remittances in part and across claims. */
class ClaimCycleTest {

  final PayerRepository payers = mock(PayerRepository.class);
  final ClaimRepository claims = mock(ClaimRepository.class);
  final RemittanceRepository remittances = mock(RemittanceRepository.class);
  final ClaimBatchRepository batches = mock(ClaimBatchRepository.class);
  final DomainEventPublisher events = mock(DomainEventPublisher.class);
  final DocumentNumbers numbers = mock(DocumentNumbers.class);
  final Payer nhif = new Payer();
  InsuranceImpl insurance;
  Object published;

  @BeforeEach
  void setUp() {
    nhif.setCode("NHIF");
    nhif.setName("NHIF");
    when(payers.findByUid(nhif.getUid())).thenReturn(Optional.of(nhif));
    when(payers.findAll()).thenReturn(List.of(nhif));
    when(numbers.format(anyString(), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn("REM-000001");
    when(remittances.save(any())).thenAnswer(call -> call.getArgument(0));
    when(events.publish(anyString(), any()))
        .thenAnswer(
            call -> {
              Function<EventMetadata, ?> factory = call.getArgument(1);
              published =
                  factory.apply(
                      new EventMetadata(
                          UUID.randomUUID(),
                          call.getArgument(0),
                          null,
                          null,
                          Instant.now(),
                          "test",
                          null));
              return published;
            });
    insurance =
        new InsuranceImpl(
            payers,
            mock(SchemeRepository.class),
            mock(MemberRepository.class),
            claims,
            batches,
            remittances,
            mock(InsuranceDirectory.class),
            numbers,
            events,
            Clock.fixed(Instant.parse("2026-11-03T10:00:00Z"), ZoneOffset.UTC),
            mock(DataSource.class));
  }

  Claim claim(String number, UUID amoxicillin, UUID vitamins) {
    Claim claim = new Claim();
    claim.setNumber(number);
    claim.setPayerUid(nhif.getUid());
    claim.setStatus(ClaimStatus.SUBMITTED);
    claim.setCustomerUid(UUID.randomUUID());
    claim.add(line(amoxicillin, "Amoxicillin 250mg", "7200.00"));
    claim.add(line(vitamins, "Vitamin C 1000mg", "1800.00"));
    when(claims.findByUid(claim.getUid())).thenReturn(Optional.of(claim));
    return claim;
  }

  static ClaimLine line(UUID product, String name, String amount) {
    ClaimLine line = new ClaimLine();
    line.setProductUid(product);
    line.setProductName(name);
    line.setQuantity(1);
    line.setAmount(new BigDecimal(amount));
    return line;
  }

  @Test
  @DisplayName(
      "a partial approval rejects part; the rejected amount goes to the patient's account when chosen")
  void partialApproval() {
    UUID amoxicillin = UUID.randomUUID();
    UUID vitamins = UUID.randomUUID();
    Claim claim = claim("CLM-000001", amoxicillin, vitamins);

    ClaimView view =
        insurance.adjudicate(
            claim.getUid(),
            new AdjudicationCommand(
                null,
                List.of(
                    new ClaimLineView(
                        vitamins, null, 1, null, BigDecimal.ZERO, "Supplements not covered")),
                "Vitamins excluded on review",
                RejectedTo.PATIENT_ACCOUNT));

    assertThat(view.status()).isEqualTo(ClaimStatus.PARTIALLY_APPROVED);
    assertThat(view.approved()).isEqualByComparingTo("7200.00");
    assertThat(published)
        .isInstanceOfSatisfying(
            ClaimAdjudicated.class,
            event -> {
              assertThat(event.rejected()).isEqualByComparingTo("1800.00");
              assertThat(event.rejectedTo()).isEqualTo("PATIENT_ACCOUNT");
              assertThat(event.customerUid()).isEqualTo(claim.getCustomerUid());
            });
    assertThat(view.lines().get(1).rejectionReason()).isEqualTo("Supplements not covered");
  }

  @Test
  @DisplayName("a remittance settles the oldest approved claims first, in part when it runs out")
  void remittanceAcrossClaims() {
    Claim first = claim("CLM-000001", UUID.randomUUID(), UUID.randomUUID());
    Claim second = claim("CLM-000002", UUID.randomUUID(), UUID.randomUUID());
    first.setApproved(new BigDecimal("9000.00"));
    first.setStatus(ClaimStatus.APPROVED);
    second.setApproved(new BigDecimal("7200.00"));
    second.setStatus(ClaimStatus.PARTIALLY_APPROVED);
    when(claims.payable(nhif.getUid())).thenReturn(List.of(first, second));

    List<ClaimView> settled =
        insurance.remit(
            new RemittanceCommand(
                nhif.getUid(), new BigDecimal("12000.00"), "NHIF-EFT-77", null, Map.of()));

    assertThat(settled).extracting(ClaimView::number).containsExactly("CLM-000001", "CLM-000002");
    assertThat(first.getStatus()).isEqualTo(ClaimStatus.PAID);
    assertThat(second.getPaid()).isEqualByComparingTo("3000.00");
    assertThat(second.getStatus()).isEqualTo(ClaimStatus.PARTIALLY_APPROVED);
    assertThat(second.due()).isEqualByComparingTo("4200.00");
  }

  @Test
  @DisplayName(
      "the FHIR export is an R4 Bundle of pharmacy Claim resources with ICD-10 diagnosis and items")
  void fhirBundle() {
    Claim claim = claim("CLM-000003", UUID.randomUUID(), UUID.randomUUID());
    claim.setDiagnosisCode("J06.9");
    claim.setMemberNumber("NHIF-0099812");
    claim.setPatientName("Neema Kileo");

    Map<String, Object> bundle =
        FhirClaims.bundle("BAT-000001", "NHIF", "Afya Pharmacy", "TZS", List.of(claim));
    String json = JsonMapper.builder().build().writeValueAsString(bundle);

    assertThat(bundle).containsEntry("resourceType", "Bundle").containsEntry("type", "collection");
    assertThat(json)
        .contains("\"resourceType\":\"Claim\"")
        .contains(FhirClaims.CLAIM_TYPE)
        .contains("\"code\":\"pharmacy\"")
        .contains(FhirClaims.ICD10)
        .contains("\"code\":\"J06.9\"")
        .contains("\"currency\":\"TZS\"")
        .contains("Amoxicillin 250mg")
        .contains("NHIF-0099812");
  }

  private ClaimView decide(Claim claim, UUID product, String approved, RejectedTo to) {
    return insurance.adjudicate(
        claim.getUid(),
        new AdjudicationCommand(
            null,
            List.of(new ClaimLineView(product, null, 1, null, new BigDecimal(approved), null)),
            null,
            to));
  }

  private Claim single() {
    Claim claim = new Claim();
    claim.setNumber("CLM-000009");
    claim.setPayerUid(nhif.getUid());
    claim.setStatus(ClaimStatus.SUBMITTED);
    claim.setCustomerUid(UUID.randomUUID());
    claim.setSaleUid(UUID.randomUUID());
    when(claims.findByUid(claim.getUid())).thenReturn(Optional.of(claim));
    return claim;
  }

  @Test
  @DisplayName("MON-4: adjudicated again, only the change in the rejected part is published")
  void readjudicationPostsTheChange() {
    UUID product = UUID.randomUUID();
    Claim claim = single();
    claim.add(line(product, "Insulin", "10000.00"));

    decide(claim, product, "6000.00", RejectedTo.WRITE_OFF);
    assertThat(published)
        .isInstanceOfSatisfying(
            ClaimAdjudicated.class,
            event -> {
              assertThat(event.writeOffChange()).isEqualByComparingTo("4000.00");
              assertThat(event.patientChange()).isEqualByComparingTo("0");
            });
    decide(claim, product, "8000.00", RejectedTo.WRITE_OFF);
    assertThat(published)
        .isInstanceOfSatisfying(
            ClaimAdjudicated.class,
            event -> {
              assertThat(event.rejected()).isEqualByComparingTo("2000.00");
              assertThat(event.writeOffChange()).isEqualByComparingTo("-2000.00");
            });
    // The rejected part moves to the patient: the write-off is given back, the patient charged.
    decide(claim, product, "8000.00", RejectedTo.PATIENT_ACCOUNT);
    assertThat(published)
        .isInstanceOfSatisfying(
            ClaimAdjudicated.class,
            event -> {
              assertThat(event.writeOffChange()).isEqualByComparingTo("-2000.00");
              assertThat(event.patientChange()).isEqualByComparingTo("2000.00");
            });
    assertThat(claim.getChargedToPatient()).isEqualByComparingTo("2000.00");
    assertThat(claim.getWrittenOff()).isEqualByComparingTo("0");
  }

  @Test
  @DisplayName("MON-5: a return on a rejected claim comes off the write-off, not off 1110 again")
  void returnOnRejectedClaim() {
    UUID product = UUID.randomUUID();
    Claim claim = single();
    claim.add(line(product, "Insulin", "10000.00"));
    when(claims.findFirstBySaleUidOrderByIdDesc(claim.getSaleUid())).thenReturn(Optional.of(claim));
    decide(claim, product, "0", RejectedTo.WRITE_OFF);
    assertThat(claim.getStatus()).isEqualTo(ClaimStatus.REJECTED);

    insurance.reduceForReturn(claim.getSaleUid(), new BigDecimal("4000.00"), "RET-1", "CASH");
    assertThat(published)
        .isInstanceOfSatisfying(
            ClaimReduced.class,
            event -> {
              assertThat(event.receivableReduction()).isEqualByComparingTo("0");
              assertThat(event.writeOffReversal()).isEqualByComparingTo("4000.00");
              assertThat(event.patientReduction()).isEqualByComparingTo("0");
            });
    assertThat(claim.getWrittenOff()).isEqualByComparingTo("6000.00");
    assertThat(claim.getClaimed()).isEqualByComparingTo("6000.00");
  }

  @Test
  @DisplayName(
      "MON-5: on an approved claim the return comes off approved − paid, then the rejection")
  void returnOnApprovedClaimIsCapped() {
    UUID product = UUID.randomUUID();
    Claim claim = single();
    claim.add(line(product, "Insulin", "10000.00"));
    when(claims.findFirstBySaleUidOrderByIdDesc(claim.getSaleUid())).thenReturn(Optional.of(claim));
    decide(claim, product, "6000.00", RejectedTo.PATIENT_ACCOUNT);
    claim.setPaid(new BigDecimal("5000.00"));

    insurance.reduceForReturn(claim.getSaleUid(), new BigDecimal("3000.00"), "RET-2", "CASH");
    assertThat(published)
        .isInstanceOfSatisfying(
            ClaimReduced.class,
            event -> {
              assertThat(event.receivableReduction()).isEqualByComparingTo("1000.00");
              assertThat(event.patientReduction()).isEqualByComparingTo("2000.00");
              assertThat(event.writeOffReversal()).isEqualByComparingTo("0");
              assertThat(event.customerUid()).isEqualTo(claim.getCustomerUid());
            });
    assertThat(claim.getApproved()).isEqualByComparingTo("5000.00");
    assertThat(claim.due()).isEqualByComparingTo("0");
    assertThat(claim.getChargedToPatient()).isEqualByComparingTo("2000.00");
  }

  @Test
  @DisplayName("MON-19: a remittance refuses a negative or zero amount for a claim")
  void negativeAllocationRefused() {
    Claim first = claim("CLM-000001", UUID.randomUUID(), UUID.randomUUID());
    Claim second = claim("CLM-000002", UUID.randomUUID(), UUID.randomUUID());
    first.setApproved(new BigDecimal("9000.00"));
    first.setStatus(ClaimStatus.APPROVED);
    second.setApproved(new BigDecimal("9000.00"));
    second.setStatus(ClaimStatus.APPROVED);
    when(claims.payable(nhif.getUid())).thenReturn(List.of(first, second));
    Map<UUID, BigDecimal> split = new java.util.LinkedHashMap<>();
    split.put(first.getUid(), new BigDecimal("9000.00"));
    split.put(second.getUid(), new BigDecimal("-4000.00"));

    assertThatThrownBy(
            () ->
                insurance.remit(
                    new RemittanceCommand(
                        nhif.getUid(), new BigDecimal("5000.00"), "EFT", null, split)))
        .isInstanceOfSatisfying(
            tz.co.pharmacy.platform.kernel.Refusal.class,
            refusal -> assertThat(refusal.reason()).isEqualTo("invalid_amount"));
    assertThat(first.getPaid()).isEqualByComparingTo("0");
  }
}
