package tz.co.pharmacy.modules.partners.ports.inbound;

/**
 * What a customer may agree to (STD-5). SMS_RECEIPTS: a text message with the receipt of each sale,
 * sent unless the customer withdrew, since the receipt is part of the sale (verify with counsel);
 * MARKETING: offers and reminders, only with consent given; INSURER_SHARING: sharing their sale and
 * prescription details with their insurer (recorded; claims follow the membership contract).
 */
public enum ConsentPurpose {
  SMS_RECEIPTS,
  MARKETING,
  INSURER_SHARING;

  /** Whether the purpose is allowed when the customer has said nothing (no record). */
  public boolean allowedWithoutRecord() {
    return this == SMS_RECEIPTS;
  }
}
