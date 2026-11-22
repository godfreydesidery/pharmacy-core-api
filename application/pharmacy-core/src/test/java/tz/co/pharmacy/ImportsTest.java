package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.IntegrationTest;

/**
 * Imports from Excel or CSV: a check writes nothing and lists every problem by row and column; an
 * import writes every row or none; products match on Code, so an export can be edited and imported
 * back.
 */
class ImportsTest extends IntegrationTest {

  private Api admin;
  private Api till;

  @BeforeEach
  void pharmacy() {
    Api anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "IM" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String email = code.toLowerCase() + "@test.local";
    String tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Import %s","legalName":"Import %s Ltd","country":"TZ",
                 "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
                 "headOffice":{"code":"HQ","name":"Head Office"},
                 "admin":{"name":"Admin","email":"%s","password":"Admin#2026x"}}
                """
                    .formatted(code, code, code, email))
            .expect(201)
            .string("$.data.uid");
    admin = anonymous.as(anonymous.signIn(email, "Admin#2026x")).in(tenant, null);
    String branch =
        admin.get("/api/v1/auth/me").expect(200).string("$.data.memberships[0].branches[0].uid");
    till = admin.in(tenant, branch);
    admin.post("/api/v1/categories", "{\"name\":\"Analgesics\"}").expect(201);
  }

  private static byte[] csv(String... lines) {
    return ("﻿" + String.join("\r\n", lines)).getBytes(StandardCharsets.UTF_8);
  }

  private int productCount() {
    return admin.get("/api/v1/products?page=1&limit=10").expect(200).json().read("$.data.total");
  }

  @Test
  @DisplayName("a check lists every problem by row and column and writes nothing")
  void checkFindsEveryProblem() {
    byte[] file =
        csv(
            "Code,Name,Type,Generic name,Category,Dosage form,Cost,Price,VAT %",
            "A1,Panadol 500mg,MEDICINE,Paracetamol,Analgesics,TABLET,300,500,0",
            "A2,Mystery pill,MEDICINE,,Analgesics,TABLET,300,500,0",
            "A3,Bad price,MEDICINE,Ibuprofen,Analgesics,TABLET,300,abc,0",
            "A4,Odd one,MEDICINE,Paracetamol,Nowhere,PILL,300,500,0",
            "A1,Copy of Panadol,MEDICINE,Paracetamol,Analgesics,TABLET,300,500,0");
    Api.Response check = admin.upload("/api/v1/products/import/check", "products.csv", file);
    check.expect(200);
    assertThat((Integer) check.json().read("$.data.rows")).isEqualTo(5);
    assertThat((Boolean) check.json().read("$.data.saved")).isFalse();
    List<Integer> rows = check.json().read("$.data.problems[*].row");
    List<String> reasons = check.json().read("$.data.problems[*].reason");
    // Row 3 fails a product rule (a medicine needs its generic name), found by the trial run
    // only when the file reads cleanly — so here the read problems come first.
    assertThat(reasons)
        .contains("not_a_number", "category_not_found", "not_allowed", "repeated_in_file");
    assertThat(rows).contains(4, 5, 6);
    List<String> columns = check.json().read("$.data.problems[*].column");
    assertThat(columns).contains("Price", "Category", "Dosage form", "Code");
    assertThat(productCount()).isZero();

    // With the reading problems fixed, the trial run finds the rule a medicine breaks.
    byte[] fixed =
        csv(
            "Code,Name,Type,Generic name,Category,Dosage form,Cost,Price,VAT %",
            "A1,Panadol 500mg,MEDICINE,Paracetamol,Analgesics,TABLET,300,500,0",
            "A2,Mystery pill,MEDICINE,,Analgesics,TABLET,300,500,0",
            "A3,Brufen 400mg,MEDICINE,Ibuprofen,Analgesics,TABLET,300,700,0");
    Api.Response trial = admin.upload("/api/v1/products/import/check", "products.csv", fixed);
    assertThat((Integer) trial.json().read("$.data.problemCount")).isEqualTo(1);
    assertThat((Integer) trial.json().read("$.data.problems[0].row")).isEqualTo(3);
    assertThat((String) trial.json().read("$.data.problems[0].column")).isEqualTo("Generic name");
    assertThat((Integer) trial.json().read("$.data.creating")).isEqualTo(3);
    assertThat(productCount()).isZero();

    // The import refuses the same file whole: nothing is written.
    Api.Response refused = admin.upload("/api/v1/products/import", "products.csv", fixed);
    assertThat((Boolean) refused.json().read("$.data.saved")).isFalse();
    assertThat(productCount()).isZero();
  }

  @Test
  @DisplayName("an import adds and updates products by Code, and an export imports back")
  void importAddsAndUpdates() {
    byte[] file =
        csv(
            "Code,Name,Type,Generic name,Category,Manufacturer,Dosage form,Cost,Price,VAT %,Active",
            ",Panadol 500mg,MEDICINE,Paracetamol,analgesics,Shelys,TABLET,300,500,0,Yes",
            "BRU-400,Brufen 400mg,MEDICINE,Ibuprofen,Analgesics,Shelys,TABLET,\"1,200\",1500,18,",
            ",Crutches (pair),MEDICAL_EQUIPMENT,,,,,20000,35000,18,No");
    Api.Response saved = admin.upload("/api/v1/products/import", "products.csv", file);
    saved.expect(200);
    assertThat((Boolean) saved.json().read("$.data.saved")).isTrue();
    assertThat((Integer) saved.json().read("$.data.creating")).isEqualTo(3);
    assertThat(productCount()).isEqualTo(3);
    String brufen = admin.get("/api/v1/products?q=Brufen").expect(200).body();
    assertThat(brufen).contains("\"sku\":\"BRU-400\"", "\"costPrice\":1200");
    // One manufacturer was added once, though two rows name it.
    assertThat(admin.get("/api/v1/manufacturers").expect(200).body().split("Shelys", -1))
        .hasSize(2);
    assertThat(admin.get("/api/v1/products?q=Crutches").expect(200).body())
        .contains("\"active\":false");

    // Export to Excel, change a price by Code in a CSV with only some columns, import back.
    byte[] workbook = admin.download("/api/v1/products/export");
    Api.Response again = admin.upload("/api/v1/products/import/check", "products.xlsx", workbook);
    assertThat((Integer) again.json().read("$.data.updating")).isEqualTo(3);
    assertThat((Integer) again.json().read("$.data.problemCount")).isZero();

    byte[] prices = csv("Code;Name;Cost;Price", "bru-400;Brufen 400mg;1200;1650");
    Api.Response changed = admin.upload("/api/v1/products/import", "prices.csv", prices);
    assertThat((Integer) changed.json().read("$.data.updating")).isEqualTo(1);
    assertThat((Boolean) changed.json().read("$.data.saved")).isTrue();
    String after = admin.get("/api/v1/products?q=Brufen").expect(200).body();
    // The price changed; columns left out (VAT, generic name) kept their values.
    assertThat(after).contains("\"sellingPrice\":1650", "\"taxRatePercent\":18", "Ibuprofen");
    assertThat(productCount()).isEqualTo(3);
  }

  @Test
  @DisplayName("the template is a workbook its own import reads; unknown columns are refused")
  void templateAndHeadings() {
    byte[] template = admin.download("/api/v1/products/import/template?lang=sw");
    assertThat(new String(template, 0, 2, StandardCharsets.ISO_8859_1)).isEqualTo("PK");
    Api.Response check = admin.upload("/api/v1/products/import/check", "template.xlsx", template);
    check.expect(200);
    // Its example row reads cleanly in Swahili headings.
    assertThat((Integer) check.json().read("$.data.problemCount")).isZero();
    assertThat((Integer) check.json().read("$.data.creating")).isEqualTo(1);

    Api.Response unknown =
        admin.upload(
            "/api/v1/products/import/check",
            "bad.csv",
            csv("Name,Price,Colour", "Gloves,1000,Blue"));
    List<String> reasons = unknown.json().read("$.data.problems[*].reason");
    assertThat(reasons).containsExactlyInAnyOrder("unknown_column", "missing_column");

    admin.upload("/api/v1/products/import/check", "old.xls", new byte[] {1, 2, 3}).expect(422);
  }

  @Test
  @DisplayName("customers, suppliers and opening stock import too")
  void otherImports() {
    Api.Response customers =
        admin.upload(
            "/api/v1/customers/import",
            "customers.csv",
            csv(
                "Code,Name,Type,Phone,Credit limit,Payment terms",
                ",Mama Neema,RETAIL,+255712000001,,COD",
                ",City Clinic,CORPORATE,+255712000002,500000,NET_30"));
    assertThat((Boolean) customers.json().read("$.data.saved")).isTrue();
    String code = admin.get("/api/v1/customers?q=Neema").expect(200).string("$.data.items[0].code");
    Api.Response renamed =
        admin.upload(
            "/api/v1/customers/import",
            "customers.csv",
            csv("Code,Name", code + ",Mama Neema Juma", "CUS-999999,Nobody"));
    assertThat((Boolean) renamed.json().read("$.data.saved")).isFalse();
    assertThat((String) renamed.json().read("$.data.problems[0].reason"))
        .isEqualTo("customer_not_found");

    Api.Response suppliers =
        admin.upload(
            "/api/v1/suppliers/import",
            "suppliers.csv",
            csv("Name,Phone,Lead time (days)", "Kariakoo Pharma,+255713000111,3"));
    assertThat((Boolean) suppliers.json().read("$.data.saved")).isTrue();

    admin
        .upload(
            "/api/v1/products/import",
            "products.csv",
            csv(
                "Code,Name,Generic name,Dosage form,Cost,Price",
                "PCM,Panadol 500mg,Paracetamol,TABLET,300,500"))
        .expect(200);
    LocalDate expiry = LocalDate.now().plusYears(1);
    Api.Response stock =
        till.upload(
            "/api/v1/stock/opening/import",
            "stock.csv",
            csv(
                "Code,Product,Batch number,Expiry date,Quantity,Unit cost",
                "PCM,Panadol,B-1," + expiry + ",100,",
                "PCM,Panadol,B-2,"
                    + "%02d/%02d/%d"
                        .formatted(expiry.getDayOfMonth(), expiry.getMonthValue(), expiry.getYear())
                    + ",50,280"));
    assertThat((Boolean) stock.json().read("$.data.saved")).isTrue();
    assertThat(till.get("/api/v1/stock?q=Panadol").expect(200).body()).contains("\"quantity\":150");

    // Opening stock is per branch: without a branch it is refused.
    admin
        .upload("/api/v1/stock/opening/import/check", "stock.csv", csv("Code,Quantity", "PCM,1"))
        .expect(403);
  }

  @Test
  @DisplayName("a price list's items are repriced or added by Code and minimum quantity")
  void priceList() {
    admin
        .upload(
            "/api/v1/products/import",
            "products.csv",
            csv(
                "Code,Name,Generic name,Dosage form,Cost,Price",
                "PCM,Panadol 500mg,Paracetamol,TABLET,300,500",
                "BRU,Brufen 400mg,Ibuprofen,TABLET,900,1500",
                "AMX,Amoxil 250mg,Amoxicillin,CAPSULE,200,400"))
        .expect(200);
    String pcm = admin.get("/api/v1/products?q=PCM").expect(200).string("$.data.items[0].uid");
    String amx = admin.get("/api/v1/products?q=AMX").expect(200).string("$.data.items[0].uid");
    String list =
        admin
            .post(
                "/api/v1/price-lists",
                """
                {"name":"Wholesale","customerType":"WHOLESALE","items":[
                  {"productUid":"%s","minQuantity":1,"price":450},
                  {"productUid":"%s","minQuantity":1,"price":350}]}
                """
                    .formatted(pcm, amx))
            .expect(201)
            .string("$.data.uid");
    String base = "/api/v1/price-lists/" + list + "/import";

    // Saving the list again with the same items reprices them in place (it used to break the
    // unique product and minimum quantity key).
    admin
        .put(
            "/api/v1/price-lists/" + list,
            """
            {"name":"Wholesale","customerType":"WHOLESALE","items":[
              {"productUid":"%s","minQuantity":1,"price":460},
              {"productUid":"%s","minQuantity":1,"price":350}]}
            """
                .formatted(pcm, amx))
        .expect(200);

    // The template holds the list's items, and imports back unchanged.
    byte[] template = admin.download(base + "/template");
    Api.Response same = admin.upload(base + "/check", "list.xlsx", template);
    assertThat((Integer) same.json().read("$.data.updating")).isEqualTo(2);
    assertThat((Integer) same.json().read("$.data.problemCount")).isZero();

    // Problems by row and column; nothing changes.
    Api.Response bad =
        admin.upload(
            base + "/check",
            "list.csv",
            csv("Code,Min quantity,Price", "PCM,0,abc", "NOPE,1,100", "BRU,5,1400", "bru,5,1300"));
    List<String> reasons = bad.json().read("$.data.problems[*].reason");
    assertThat(reasons)
        .contains("invalid_quantity", "not_a_number", "product_not_found", "repeated_in_file");

    // Reprice one, add a quantity break and a new product; the item not named stays.
    Api.Response saved =
        admin.upload(
            base,
            "list.csv",
            csv("Code,Min quantity,Price", "PCM,1,420", "PCM,10,400", "BRU,,1400"));
    assertThat((Boolean) saved.json().read("$.data.saved")).isTrue();
    assertThat((Integer) saved.json().read("$.data.creating")).isEqualTo(2);
    assertThat((Integer) saved.json().read("$.data.updating")).isEqualTo(1);
    Api.Response lists = admin.get("/api/v1/price-lists").expect(200);
    List<Object> items = lists.json().read("$.data[0].items");
    assertThat(items).hasSize(4);
    assertThat(lists.body()).contains("420", "400", "1400", "350");

    admin
        .upload(
            "/api/v1/price-lists/" + UUID.randomUUID() + "/import/check",
            "x.csv",
            csv("Code,Price", "PCM,1"))
        .expect(404);
  }
}
