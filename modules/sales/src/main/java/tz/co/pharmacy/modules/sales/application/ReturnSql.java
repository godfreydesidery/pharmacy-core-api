package tz.co.pharmacy.modules.sales.application;

/** SQL shared by the shift and cashier reports about what returns paid out. */
final class ReturnSql {

  private ReturnSql() {}

  /**
   * The cash that left the drawer for the return {@code r}: its CASH share (a return paid for
   * partly by card gives only the cash share back in cash), plus the exchange difference of a
   * foreign cash refund. A return from before the split (no refund rows) paid it all by its method.
   */
  static String cashPaidOut(String r) {
    return "(case when exists (select 1 from sales.sale_return_refunds f where f.return_id = "
        + r
        + ".id) then (select coalesce(sum(f.amount), 0) from sales.sale_return_refunds f"
        + " where f.return_id = "
        + r
        + ".id and f.method = 'CASH') when "
        + r
        + ".refund_method = 'CASH' then "
        + r
        + ".refund_total else 0 end + case when "
        + r
        + ".refund_method = 'CASH' then coalesce("
        + r
        + ".fx_difference, 0) else 0 end)";
  }
}
