package tz.co.pharmacy.modules.purchasing.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.persistence.TenantEntity;

@Getter
@Entity
@Table(schema = "purchasing", name = "purchase_order_lines")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PurchaseOrderLine extends TenantEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "purchase_order_id")
  private PurchaseOrder purchaseOrder;

  private UUID productUid;
  private String productName;
  private int quantity;
  private int receivedQuantity;
  private BigDecimal unitCost;
  private BigDecimal lineTotal;

  PurchaseOrderLine(
      PurchaseOrder purchaseOrder,
      UUID productUid,
      String productName,
      int quantity,
      BigDecimal unitCost) {
    this.purchaseOrder = purchaseOrder;
    this.productUid = productUid;
    this.productName = productName;
    this.quantity = quantity;
    // In the document's currency (USD 2 decimals, UGX none), not necessarily the base one.
    String currency = purchaseOrder.getCurrency();
    this.unitCost = Amounts.money(unitCost, currency);
    this.lineTotal = Amounts.times(this.unitCost, quantity, currency);
  }

  public int outstanding() {
    return Math.max(0, quantity - receivedQuantity);
  }

  /** Records {@code received} more units; the caller checked it is no more than outstanding. */
  public void receive(int received) {
    receivedQuantity += received;
  }
}
