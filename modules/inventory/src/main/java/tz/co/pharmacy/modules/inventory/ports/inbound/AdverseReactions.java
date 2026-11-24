package tz.co.pharmacy.modules.inventory.ports.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;

/**
 * Adverse drug reaction reports (pharmacovigilance, STD-8): what a patient suffered after a
 * medicine of a batch, for the pharmacy to send to TMDA. The fields follow TMDA's yellow form as
 * far as the sources read show it; verify the form and its fields with TMDA before use.
 */
public interface AdverseReactions {

  enum Sex {
    FEMALE,
    MALE
  }

  enum Seriousness {
    SERIOUS,
    NOT_SERIOUS
  }

  /** Why a reaction is serious (the ICH E2A criteria TMDA's form asks for). */
  enum SeriousReason {
    DEATH,
    LIFE_THREATENING,
    HOSPITALISATION,
    DISABILITY,
    CONGENITAL_ANOMALY,
    OTHER_MEDICALLY_IMPORTANT
  }

  enum Outcome {
    RECOVERED,
    RECOVERING,
    NOT_RECOVERED,
    RECOVERED_WITH_SEQUELAE,
    FATAL,
    UNKNOWN
  }

  /**
   * A report. The patient is optional ({@code customerUid}, name, age, sex, weight) and is
   * anonymised with the customer. {@code productName} is the product's name now, read by the caller
   * from the catalogue. {@code reporterName} null: the signed-in user.
   */
  record ReportCommand(
      UUID customerUid,
      String patientName,
      Integer patientAge,
      Sex patientSex,
      BigDecimal patientWeightKg,
      UUID productUid,
      String productName,
      String batchNumber,
      String dose,
      String route,
      String indication,
      LocalDate therapyStarted,
      LocalDate therapyStopped,
      String reaction,
      LocalDate onsetDate,
      Seriousness seriousness,
      SeriousReason seriousReason,
      Outcome outcome,
      String reporterName,
      String reporterProfession,
      String reporterPhone,
      String note) {}

  record AdverseReactionView(
      UUID uid,
      String number,
      UUID branchUid,
      UUID customerUid,
      String patientName,
      Integer patientAge,
      Sex patientSex,
      BigDecimal patientWeightKg,
      UUID productUid,
      String productName,
      String batchNumber,
      String dose,
      String route,
      String indication,
      LocalDate therapyStarted,
      LocalDate therapyStopped,
      String reaction,
      LocalDate onsetDate,
      Seriousness seriousness,
      SeriousReason seriousReason,
      Outcome outcome,
      String reporterName,
      String reporterProfession,
      String reporterPhone,
      String note,
      Instant createdAt,
      String createdBy) {}

  /**
   * Records a report at the current branch, numbered ADR-000001. 422 onset_in_future,
   * serious_reason_required, invalid_therapy_dates, invalid_reaction, invalid_patient_age.
   */
  AdverseReactionView report(ReportCommand command);

  /**
   * Newest first, of the given branches (null: all); {@code q} matches the number, product, batch
   * or patient.
   */
  Page<AdverseReactionView> list(String q, java.util.Set<UUID> branches, PageQuery query);

  Optional<AdverseReactionView> find(UUID uid);
}
