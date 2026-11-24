package tz.co.pharmacy.modules.insurance.domain;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tz.co.pharmacy.platform.kernel.Amounts;

/**
 * A batch as a FHIR R4 Bundle (type "collection") of Claim resources: claim type pharmacy, the
 * member as insurance coverage, the ICD-10 diagnosis and one item per claim line. Built as plain
 * maps so any JSON writer serialises it.
 *
 * <p>FHIR forbids empty values: a string is never "" and an element or array is never empty (R4
 * JSON representation, section 2.6.2). An element whose value is absent is left out (STD-20), and
 * so is a structure left with nothing in it.
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
          element(
              "fullUrl",
              "urn:uuid:" + claim.getUid(),
              "resource",
              claim(claim, payerName, provider, currency)));
    }
    Map<String, Object> bundle =
        element("resourceType", "Bundle", "id", batchNumber, "type", "collection");
    bundle.put("total", claims.size());
    if (!entries.isEmpty()) {
      bundle.put("entry", entries);
    }
    return bundle;
  }

  static Map<String, Object> claim(
      Claim claim, String payerName, String provider, String currency) {
    List<Object> items = new ArrayList<>();
    int sequence = 1;
    for (ClaimLine line : claim.getLines()) {
      items.add(
          element(
              "sequence", sequence++,
              "productOrService", element("text", line.getProductName()),
              "quantity", element("value", line.getQuantity()),
              "net", money(line.getAmount(), currency)));
    }
    boolean diagnosed = claim.getDiagnosisCode() != null && !claim.getDiagnosisCode().isBlank();
    return element(
        "resourceType",
        "Claim",
        "id",
        claim.getUid() == null ? null : claim.getUid().toString(),
        "identifier",
        list(element("system", "urn:pharmacy-core:claim", "value", claim.getNumber())),
        "status",
        "active",
        "type",
        element("coding", list(element("system", CLAIM_TYPE, "code", "pharmacy"))),
        "use",
        "claim",
        "patient",
        element(
            "display",
            claim.getPatientName(),
            "identifier",
            element("value", claim.getMemberNumber())),
        "created",
        claim.getCreatedAt() == null
            ? null
            : claim.getCreatedAt().atOffset(ZoneOffset.UTC).toLocalDate().toString(),
        "insurer",
        element("display", payerName),
        "provider",
        element("display", provider),
        "priority",
        element("coding", list(element("code", "normal"))),
        "insurance",
        list(
            element(
                "sequence",
                1,
                "focal",
                true,
                "coverage",
                element("display", claim.getMemberNumber()),
                "preAuthRef",
                list(claim.getPreauthNumber()))),
        "diagnosis",
        diagnosed
            ? list(
                element(
                    "sequence",
                    1,
                    "diagnosisCodeableConcept",
                    element(
                        "coding",
                        list(element("system", ICD10, "code", claim.getDiagnosisCode())))))
            : null,
        "item",
        items.isEmpty() ? null : items,
        "total",
        money(claim.getClaimed(), currency),
        "supportingInfo",
        // The sale the claim is for; without its number there is nothing to support.
        present(claim.getSaleNumber())
            ? list(
                element(
                    "sequence",
                    1,
                    "category",
                    element("text", "sale"),
                    "valueString",
                    claim.getSaleNumber()))
            : null);
  }

  private static Map<String, Object> money(BigDecimal value, String currency) {
    return element("value", value == null ? null : Amounts.money(value), "currency", currency);
  }

  /**
   * An element of names and values in order, leaving out every absent value (null, a blank string,
   * an empty element or list); null when nothing is left. A structure left with only its {@code
   * sequence} (or the like) still counts as something, which {@link #list} then judges.
   */
  static Map<String, Object> element(Object... namesAndValues) {
    Map<String, Object> element = new LinkedHashMap<>();
    for (int i = 0; i + 1 < namesAndValues.length; i += 2) {
      Object value = namesAndValues[i + 1];
      if (present(value)) {
        element.put((String) namesAndValues[i], value);
      }
    }
    return element.isEmpty() ? null : element;
  }

  /**
   * The present values in order; null when none is. An element holding nothing but a {@code
   * sequence} (an insurance or supporting entry whose content is absent) is not present either.
   */
  static List<Object> list(Object... values) {
    List<Object> list = new ArrayList<>();
    for (Object value : values) {
      if (present(value) && !onlySequence(value)) {
        list.add(value);
      }
    }
    return list.isEmpty() ? null : list;
  }

  private static boolean onlySequence(Object value) {
    return value instanceof Map<?, ?> map
        && map.keySet().stream().allMatch(key -> "sequence".equals(key) || "focal".equals(key));
  }

  private static boolean present(Object value) {
    if (value == null) {
      return false;
    }
    if (value instanceof String text) {
      return !text.isBlank();
    }
    if (value instanceof Map<?, ?> map) {
      return !map.isEmpty();
    }
    if (value instanceof Collection<?> collection) {
      return !collection.isEmpty();
    }
    return true;
  }
}
