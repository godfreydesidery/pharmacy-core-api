package tz.co.pharmacy.modules.inventory.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.inventory.domain.AdverseReaction;
import tz.co.pharmacy.modules.inventory.persistence.AdverseReactionRepository;
import tz.co.pharmacy.modules.inventory.ports.inbound.AdverseReactions;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.kernel.TenantCalendar;
import tz.co.pharmacy.platform.persistence.DocumentNumbers;
import tz.co.pharmacy.platform.persistence.Keyset;
import tz.co.pharmacy.platform.persistence.Specs;

@Service
@RequiredArgsConstructor
class AdverseReactionsImpl implements AdverseReactions {

  private final AdverseReactionRepository reports;
  private final DocumentNumbers numbers;
  private final TenantCalendar calendar;

  @Override
  @Transactional
  public AdverseReactionView report(ReportCommand c) {
    UUID branch = ExecutionContext.requireBranch();
    Checks.required(c.productUid(), "productUid");
    LocalDate today = calendar.today();
    LocalDate onset = Checks.required(c.onsetDate(), "onsetDate");
    if (onset.isAfter(today)) {
      throw Refusal.invalid(
          "onset_in_future", "The reaction cannot start after today.", "onsetDate");
    }
    Seriousness seriousness = Checks.required(c.seriousness(), "seriousness");
    if (seriousness == Seriousness.SERIOUS && c.seriousReason() == null) {
      throw Refusal.invalid(
          "serious_reason_required",
          "Say why the reaction is serious (death, life-threatening, hospitalisation, ...).",
          "seriousReason");
    }
    if (c.therapyStarted() != null
        && c.therapyStopped() != null
        && c.therapyStopped().isBefore(c.therapyStarted())) {
      throw Refusal.invalid(
          "invalid_therapy_dates",
          "The medicine cannot be stopped before it was started.",
          "therapyStopped");
    }
    Integer age = c.patientAge();
    if (age != null && (age < 0 || age > 150)) {
      throw Refusal.invalid(
          "invalid_patient_age", "patientAge must be between 0 and 150.", "patientAge");
    }
    BigDecimal weight = c.patientWeightKg();
    if (weight != null
        && (weight.signum() <= 0 || weight.compareTo(BigDecimal.valueOf(1000)) >= 0)) {
      throw Refusal.invalid(
          "invalid_patient_weight_kg",
          "patientWeightKg must be more than 0 and less than 1000.",
          "patientWeightKg");
    }
    String reporter =
        c.reporterName() == null || c.reporterName().isBlank()
            ? ExecutionContext.actor().name()
            : c.reporterName();
    ReportCommand valid =
        new ReportCommand(
            c.customerUid(),
            Checks.optionalText(c.patientName(), "patientName", 200),
            age,
            c.patientSex(),
            weight == null ? null : weight.setScale(1, java.math.RoundingMode.HALF_UP),
            c.productUid(),
            Checks.text(c.productName(), "productName", 300),
            Checks.optionalText(c.batchNumber(), "batchNumber", 100),
            Checks.optionalText(c.dose(), "dose", 200),
            Checks.optionalText(c.route(), "route", 100),
            Checks.optionalText(c.indication(), "indication", 500),
            c.therapyStarted(),
            c.therapyStopped(),
            Checks.text(c.reaction(), "reaction", 4000),
            onset,
            seriousness,
            seriousness == Seriousness.SERIOUS ? c.seriousReason() : null,
            Checks.required(c.outcome(), "outcome"),
            Checks.text(reporter, "reporterName", 200),
            Checks.optionalText(c.reporterProfession(), "reporterProfession", 100),
            Checks.optionalText(c.reporterPhone(), "reporterPhone", 40),
            Checks.optionalText(c.note(), "note", 2000));
    return view(reports.save(new AdverseReaction(numbers.format("ADR", 6), branch, valid)));
  }

  @Override
  @Transactional(readOnly = true)
  public Page<AdverseReactionView> list(String q, Set<UUID> branches, PageQuery query) {
    return Keyset.page(
        reports,
        Specs.<AdverseReaction>containsAny(q, "number", "productName", "batchNumber", "patientName")
            .and(branches == null ? Specs.all() : Specs.in("branchUid", branches)),
        query,
        AdverseReactionsImpl::view);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<AdverseReactionView> find(UUID uid) {
    return reports.findByUid(uid).map(AdverseReactionsImpl::view);
  }

  private static AdverseReactionView view(AdverseReaction r) {
    return new AdverseReactionView(
        r.getUid(),
        r.getNumber(),
        r.getBranchUid(),
        r.getCustomerUid(),
        r.getPatientName(),
        r.getPatientAge(),
        r.getPatientSex(),
        r.getPatientWeightKg(),
        r.getProductUid(),
        r.getProductName(),
        r.getBatchNumber(),
        r.getDose(),
        r.getRoute(),
        r.getIndication(),
        r.getTherapyStarted(),
        r.getTherapyStopped(),
        r.getReaction(),
        r.getOnsetDate(),
        r.getSeriousness(),
        r.getSeriousReason(),
        r.getOutcome(),
        r.getReporterName(),
        r.getReporterProfession(),
        r.getReporterPhone(),
        r.getNote(),
        r.getCreatedAt(),
        r.getCreatedBy());
  }
}
