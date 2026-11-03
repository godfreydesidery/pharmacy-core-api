package tz.co.pharmacy.modules.sales.ports.inbound;

import java.time.LocalDate;

/**
 * {@code q} matches the number and the customer's name; {@code from} and {@code to} are UTC dates.
 */
public record SaleFilter(String q, SaleStatus status, LocalDate from, LocalDate to) {}
