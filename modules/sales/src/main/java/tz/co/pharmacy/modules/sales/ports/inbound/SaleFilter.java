package tz.co.pharmacy.modules.sales.ports.inbound;

import java.time.LocalDate;

/**
 * {@code q} matches the number and the customer's name; {@code from} and {@code to} are UTC dates.
 */
/** {@code since}, when given, keeps sales made from that moment on, such as the last 24 hours. */
public record SaleFilter(
    String q, SaleStatus status, LocalDate from, LocalDate to, java.time.Instant since) {

  public SaleFilter(String q, SaleStatus status, LocalDate from, LocalDate to) {
    this(q, status, from, to, null);
  }
}
