package tz.co.pharmacy.modules.tenancy.ports.inbound;

/** How input VAT that serves both taxable and exempt sales is split (STD-2, partial exemption). */
public enum VatApportionmentMethod {
  /** The taxable share of sales: a provisional ratio each month and a true-up at year end. */
  AUTOMATIC,
  /** A recoverable percentage agreed with TRA or the pharmacy's accountant. */
  FIXED
}
