package tz.co.pharmacy.modules.sales.ports.inbound;

import java.time.LocalDate;

/**
 * {@code q} matches the number and the customer's name; {@code from} and {@code to} are UTC dates.
 * {@code since}, when given, keeps sales made from that moment on, such as the last 24 hours.
 * {@code idempotencyKey}, when given, keeps the one sale made with that key (WEB-2: a till that
 * lost the answer to its checkout asks whether the sale was made).
 */
public record SaleFilter(
    String q,
    SaleStatus status,
    LocalDate from,
    LocalDate to,
    java.time.Instant since,
    String idempotencyKey) {

  public SaleFilter(
      String q, SaleStatus status, LocalDate from, LocalDate to, java.time.Instant since) {
    this(q, status, from, to, since, null);
  }

  public SaleFilter(String q, SaleStatus status, LocalDate from, LocalDate to) {
    this(q, status, from, to, null, null);
  }
}
