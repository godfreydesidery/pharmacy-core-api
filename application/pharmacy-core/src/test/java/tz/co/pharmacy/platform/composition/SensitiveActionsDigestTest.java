package tz.co.pharmacy.platform.composition;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.modules.notifications.ports.inbound.Recipient;
import tz.co.pharmacy.platform.persistence.audit.AuditEntryView;

/**
 * The owner's digest of sensitive actions reads in the recipient's language, headings and listed
 * actions alike, and shows an owner's approval of their own request as a self-approval.
 */
class SensitiveActionsDigestTest {

  private static final ZoneId DAR = ZoneId.of("Africa/Dar_es_Salaam");
  private static final LocalDate DAY = LocalDate.of(2026, 11, 23);

  private static AuditEntryView approval(String auditAction, String action, boolean self) {
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("action", action);
    after.put("amount", "80000.00");
    after.put("reference", "VCH-1");
    after.put("approvedBy", "Juma Owner");
    after.put("selfApproved", self);
    return entry(auditAction, "Approval", after);
  }

  private static AuditEntryView entry(String action, String type, Map<String, Object> after) {
    return new AuditEntryView(
        UUID.randomUUID(),
        DAY.atTime(10, 12).atZone(DAR).toInstant(),
        "juma@test.local",
        action,
        type,
        UUID.randomUUID(),
        UUID.randomUUID(),
        null,
        null,
        after,
        null,
        null,
        null,
        null,
        null,
        "SUCCESS");
  }

  @Test
  @DisplayName("an owner's approval of their own request is listed and counted as a self-approval")
  void selfApprovals() {
    List<AuditEntryView> entries =
        List.of(
            approval("APPROVAL_SELF_GRANTED", "VOUCHER", true),
            approval("APPROVAL_GRANTED", "REFUND", false));
    assertThat(SensitiveActionsDigest.ACTIONS).contains("APPROVAL_SELF_GRANTED");

    Map<String, Object> english = SensitiveActionsDigest.params("Afya", DAY, DAR, entries, false);
    assertThat((String) english.get("summary"))
        .contains("Approvals the owner gave their own requests: 1")
        .contains("Approvals granted: 1");
    assertThat((String) english.get("items"))
        .contains("10:12 Self-approved (own request) voucher 80000.00 — VCH-1 (Juma Owner)")
        .contains("10:12 Approved refund 80000.00");
    // A self-approved deep discount is a self-approval first.
    assertThat(
            SensitiveActionsDigest.item(approval("APPROVAL_SELF_GRANTED", "DEEP_DISCOUNT", true))
                .kind())
        .isEqualTo(SensitiveActionsDigest.Kind.SELF_APPROVALS);
  }

  @Test
  @DisplayName("a Swahili reader gets the headings, the actions and the date in Swahili")
  void swahili() {
    Map<String, Object> adjusted = new LinkedHashMap<>();
    adjusted.put("product", "Digest Syrup");
    adjusted.put("quantityChange", -1);
    adjusted.put("reason", "DAMAGE");
    List<AuditEntryView> entries =
        List.of(
            approval("APPROVAL_SELF_GRANTED", "VOUCHER", true),
            approval("APPROVAL_GRANTED", "SHIFT_VARIANCE", false),
            entry("STOCK_ADJUSTED", "StockMovement", adjusted));

    Map<String, Object> sw = SensitiveActionsDigest.params("Afya", DAY, DAR, entries, true);
    String summary = (String) sw.get("summary");
    String items = (String) sw.get("items");
    assertThat(summary)
        .contains("Idhini za mmiliki kwa maombi yake mwenyewe: 1")
        .contains("Idhini zilizotolewa: 1")
        .contains("Kufuta, kurekebisha na kuharibu bidhaa: 1")
        .doesNotContain("Approvals")
        .doesNotContain("Write-offs");
    assertThat(items)
        .contains("Ameidhinisha ombi lake mwenyewe: vocha 80000.00")
        .contains("Imeidhinishwa: kufunga zamu yenye tofauti ya fedha 80000.00")
        .contains("Bidhaa zimerekebishwa: Digest Syrup -1")
        .doesNotContain("Approved")
        .doesNotContain("Stock adjusted");
    assertThat(sw.get("date")).asString().startsWith("23 ").endsWith(" 2026");
    assertThat(sw.get("count")).isEqualTo(3);

    // English stays as it was for an English reader.
    Map<String, Object> en = SensitiveActionsDigest.params("Afya", DAY, DAR, entries, false);
    assertThat(en.get("date")).isEqualTo("23 Nov 2026");
    assertThat((String) en.get("items")).contains("Stock adjusted: Digest Syrup -1 (damage)");
  }

  @Test
  @DisplayName("every approval action has a Swahili name")
  void everyActionInSwahili() {
    for (String action :
        List.of(
            "REFUND",
            "LATE_RETURN",
            "STOCK_WRITE_OFF",
            "CONTROLLED_WRITE_OFF",
            "LATE_OPENING_STOCK",
            "VOUCHER",
            "DEEP_DISCOUNT",
            "DEEP_PROMOTION",
            "PERIOD_UNLOCK",
            "SCHEDULE_CHANGE",
            "CREDIT_WRITE_OFF",
            "SHIFT_VARIANCE",
            "TRANSFER_SHORTFALL")) {
      assertThat(tz.co.pharmacy.platform.approval.ApprovalActions.known(action)).isTrue();
      assertThat(SensitiveActionsDigest.APPROVAL_ACTIONS_SW).containsKey(action);
    }
  }

  @Test
  @DisplayName("the recipient's locale picks the language; none is English")
  void recipientLanguage() {
    assertThat(SensitiveActionsDigest.swahili(new Recipient(null, "A", null, null, "sw"))).isTrue();
    assertThat(SensitiveActionsDigest.swahili(new Recipient(null, "A", null, null, "sw-TZ")))
        .isTrue();
    assertThat(SensitiveActionsDigest.swahili(new Recipient(null, "A", null, null, "en")))
        .isFalse();
    assertThat(SensitiveActionsDigest.swahili(new Recipient(null, "A", null, null, null)))
        .isFalse();
  }
}
