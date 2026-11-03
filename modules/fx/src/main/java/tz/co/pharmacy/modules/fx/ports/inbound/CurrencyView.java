package tz.co.pharmacy.modules.fx.ports.inbound;

/** An ISO 4217 currency: {@code minorUnits} is the number of decimals amounts are rounded to. */
public record CurrencyView(String code, String name, int minorUnits, String symbol) {}
