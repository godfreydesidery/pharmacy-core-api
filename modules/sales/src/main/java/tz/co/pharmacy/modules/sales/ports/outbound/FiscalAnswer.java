package tz.co.pharmacy.modules.sales.ports.outbound;

/**
 * The tax authority's answer to a {@link FiscalRequest}: issued, with the document's number, its
 * verification code and the QR code's payload (often a verification URL), or refused for good with
 * the authority's reason. A failure to answer is an exception, not an answer.
 */
public record FiscalAnswer(
    boolean issued,
    String receiptNumber,
    String verificationCode,
    String qrPayload,
    String refusal) {

  public static FiscalAnswer issued(
      String receiptNumber, String verificationCode, String qrPayload) {
    return new FiscalAnswer(true, receiptNumber, verificationCode, qrPayload, null);
  }

  public static FiscalAnswer refused(String reason) {
    return new FiscalAnswer(false, null, null, null, reason);
  }
}
