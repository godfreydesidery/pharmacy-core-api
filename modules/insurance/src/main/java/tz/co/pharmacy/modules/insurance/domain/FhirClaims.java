package tz.co.pharmacy.modules.insurance.domain;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tz.co.pharmacy.platform.kernel.Amounts;

/**
 * A batch as a FHIR R4 Bundle (type "collection") of Claim resources: claim type pharmacy, the
 * member as insurance coverage, the ICD-10 diagnosis and one item per claim line. Built as plain
 * maps so any JSON writer serialises it.
 */
public final class FhirClaims {

  public static final String CLAIM_TYPE = "http://terminology.hl7.org/CodeSystem/claim-type";
  public static final String ICD10 = "http://hl7.org/fhir/sid/icd-10";

  private FhirClaims() {}

  public static Map<String, Object> bundle(
      String batchNumber, String payerName, String provider, String currency, List<Claim> claims) {
    List<Object> entries = new ArrayList<>();
    for (Claim claim : claims) {
      entries.add(
          Map.of(
              "fullUrl",
              "urn:uuid:" + claim.getUid(),
              "resource",
              claim(claim, payerName, provider, currency)));
    }
    Map<String, Object> bundle = new LinkedHashMap<>();
    bundle.put("resourceType", "Bundle");
    bundle.put("id", batchNumber);
    bundle.put("type", "collection");
    bundle.put("total", claims.size());
    bundle.put("entry", entries);
    return bundle;
  }

  static Map<String, Object> claim(
      Claim claim, String payerName, String provider, String currency) {
    Map<String, Object> resource = new LinkedHashMap<>();
    resource.put("resourceType", "Claim");
    resource.put("id", claim.getUid().toString());
    resource.put(
        "identifier",
        List.of(Map.of("system", "urn:pharmacy-core:claim", "value", claim.getNumber())));
    resource.put("status", "active");
    resource.put(
        "type", Map.of("coding", List.of(Map.of("system", CLAIM_TYPE, "code", "pharmacy"))));
    resource.put("use", "claim");
    resource.put(
        "patient",
        Map.of(
            "display", nullToEmpty(claim.getPatientName()),
            "identifier", Map.of("value", nullToEmpty(claim.getMemberNumber()))));
    resource.put(
        "created",
        claim.getCreatedAt() == null
            ? null
            : claim.getCreatedAt().atOffset(ZoneOffset.UTC).toLocalDate().toString());
    resource.put("insurer", Map.of("display", payerName));
    resource.put("provider", Map.of("display", provider));
    resource.put("priority", Map.of("coding", List.of(Map.of("code", "normal"))));
    resource.put(
        "insurance",
        List.of(
            Map.of(
                "sequence",
                1,
                "focal",
                true,
                "coverage",
                Map.of("display", nullToEmpty(claim.getMemberNumber())),
                "preAuthRef",
                claim.getPreauthNumber() == null ? List.of() : List.of(claim.getPreauthNumber()))));
    if (claim.getDiagnosisCode() != null && !claim.getDiagnosisCode().isBlank()) {
      resource.put(
          "diagnosis",
          List.of(
              Map.of(
                  "sequence",
                  1,
                  "diagnosisCodeableConcept",
                  Map.of(
                      "coding",
                      List.of(Map.of("system", ICD10, "code", claim.getDiagnosisCode()))))));
    }
    List<Object> items = new ArrayList<>();
    int sequence = 1;
    for (ClaimLine line : claim.getLines()) {
      items.add(
          Map.of(
              "sequence", sequence++,
              "productOrService", Map.of("text", nullToEmpty(line.getProductName())),
              "quantity", Map.of("value", line.getQuantity()),
              "net", money(line.getAmount(), currency)));
    }
    resource.put("item", items);
    resource.put("total", money(claim.getClaimed(), currency));
    resource.put(
        "supportingInfo",
        List.of(
            Map.of(
                "sequence", 1,
                "category", Map.of("text", "sale"),
                "valueString", nullToEmpty(claim.getSaleNumber()))));
    return resource;
  }

  private static Map<String, Object> money(BigDecimal value, String currency) {
    return Map.of("value", Amounts.money(value), "currency", currency);
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
