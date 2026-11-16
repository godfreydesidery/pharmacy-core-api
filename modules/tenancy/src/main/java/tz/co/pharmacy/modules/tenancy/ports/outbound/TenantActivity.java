package tz.co.pharmacy.modules.tenancy.ports.outbound;

/** Whether the current tenant has traded yet (finance). */
public interface TenantActivity {

  /** True once anything has been posted to the current tenant's books. */
  boolean hasPostedTransactions();
}
