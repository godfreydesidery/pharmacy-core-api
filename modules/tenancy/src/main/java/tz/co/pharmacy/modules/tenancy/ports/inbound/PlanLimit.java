package tz.co.pharmacy.modules.tenancy.ports.inbound;

/** A quantity a plan may cap; a null cap is unlimited. */
public enum PlanLimit {
  BRANCHES,
  USERS,
  PRODUCTS,
  SMS
}
