package tz.co.pharmacy.platform.composition;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.sales.ports.inbound.FiscalDocumentKind;
import tz.co.pharmacy.modules.sales.ports.outbound.FiscalAnswer;
import tz.co.pharmacy.modules.sales.ports.outbound.FiscalReceipts;
import tz.co.pharmacy.modules.sales.ports.outbound.FiscalRequest;

/**
 * A simulated tax authority, {@code pharmacy.integrations.fiscal.provider=simulator}, for training,
 * the simulated world and the tests (STD-1). It talks to nobody: it answers every document at once
 * with numbers worked out from the document itself, so a run is repeatable and a retry gets the
 * same answer, with no memory between calls.
 *
 * <ul>
 *   <li>receipt number: {@code SIM-<branch>-R00000042} (R a receipt, C a credit note) from the
 *       branch's global counter; a Z report's {@code SIM-<branch>-Z20261124} from its day;
 *   <li>verification code: the first 10 hex digits of SHA-256 over the document uid, the number,
 *       the counter and the total, upper case;
 *   <li>QR payload: a text that says it is simulated, with the TIN, VRN, number, counters, day,
 *       total, tax and code. It is not a TRA verification URL: the real format comes with TRA's VFD
 *       specification.
 * </ul>
 *
 * A seller TIN of {@value #REFUSED_TIN} is refused for good, so the FAILED path can be tried.
 *
 * <p>It exists only under the {@code sim}, {@code local} or {@code test} profile, as the payment
 * simulator (adversarial review INT-15); elsewhere {@link SimulatedFiscalReceiptsRefused} stops the
 * start.
 */
@Component
@Profile({"sim", "local", "test"})
@ConditionalOnProperty(name = "pharmacy.integrations.fiscal.provider", havingValue = "simulator")
class SimulatedFiscalReceipts implements FiscalReceipts {

  static final String REFUSED_TIN = "000000000";

  private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

  @Override
  public FiscalAnswer issue(FiscalRequest request) {
    if (request.seller() != null && REFUSED_TIN.equals(request.seller().tin())) {
      return FiscalAnswer.refused("SIMULATED: the TIN " + REFUSED_TIN + " is not registered");
    }
    String branch = request.branchCode() == null ? "BR" : request.branchCode();
    String number =
        request.kind() == FiscalDocumentKind.Z_REPORT
            ? "SIM-" + branch + "-Z" + DAY.format(request.businessDate())
            : "SIM-"
                + branch
                + "-"
                + switch (request.kind()) {
                  case CREDIT_NOTE -> "C";
                  case DEBIT_NOTE -> "D";
                  default -> "R";
                }
                + String.format(Locale.ROOT, "%08d", request.globalCounter());
    BigDecimal total = request.totals() == null ? BigDecimal.ZERO : request.totals().total();
    BigDecimal tax = request.totals() == null ? BigDecimal.ZERO : request.totals().tax();
    String code =
        sha256(
                request.documentUid()
                    + "|"
                    + number
                    + "|"
                    + request.globalCounter()
                    + "|"
                    + total.stripTrailingZeros().toPlainString())
            .substring(0, 10)
            .toUpperCase(Locale.ROOT);
    FiscalRequest.Seller seller = request.seller();
    String qr =
        String.join(
            "|",
            "SIMULATED FISCAL " + request.kind(),
            "TIN:" + (seller == null || seller.tin() == null ? "" : seller.tin()),
            "VRN:" + (seller == null || seller.vrn() == null ? "" : seller.vrn()),
            "NO:" + number,
            "GC:" + request.globalCounter(),
            "DC:" + (request.dailyCounter() == null ? "" : request.dailyCounter()),
            "DATE:" + request.businessDate(),
            "TOTAL:" + total.toPlainString(),
            "TAX:" + tax.toPlainString(),
            "CODE:" + code);
    return FiscalAnswer.issued(number, code, qr);
  }

  @Override
  public String provider() {
    return "simulator";
  }

  private static String sha256(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
