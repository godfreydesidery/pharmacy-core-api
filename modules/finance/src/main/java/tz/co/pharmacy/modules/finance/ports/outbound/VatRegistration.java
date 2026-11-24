package tz.co.pharmacy.modules.finance.ports.outbound;

/** Whether the pharmacy is registered for VAT (tenancy settings, STD-2). */
public interface VatRegistration {

  /** Registered: it books input VAT (1150) on expenses and purchases and owes output VAT (2100). */
  boolean vatRegistered();
}
