package tz.co.pharmacy.modules.catalogue.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The category tree a new pharmacy starts with: top-level groups and their first sub-categories.
 */
final class DefaultCategories {

  static final int MAX_DEPTH = 3;

  static final Map<String, List<String>> TREE = tree();

  private DefaultCategories() {}

  private static Map<String, List<String>> tree() {
    Map<String, List<String>> tree = new LinkedHashMap<>();
    tree.put(
        "Medicines",
        List.of(
            "Analgesics",
            "Antibiotics",
            "Antimalarials",
            "Antihistamines",
            "Antidiabetics",
            "Cardiovascular",
            "Respiratory",
            "Gastrointestinal",
            "Dermatologicals"));
    tree.put(
        "Medical Equipment",
        List.of(
            "Mobility Aids", "Diagnostic Devices", "Respiratory Devices", "Orthopaedic Supports"));
    tree.put("Medical Supplies", List.of("Dressings", "Syringes & Needles", "Gloves"));
    tree.put("Personal Care & Cosmetics", List.of());
    tree.put("Supplements & Nutrition", List.of());
    tree.put("Mother & Baby", List.of());
    return tree;
  }
}
