package tz.co.pharmacy.modules.catalogue.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/**
 * A price list: the default for a customer type, or one customer's special prices. Items set a
 * product's price from a minimum quantity (quantity breaks); products without an item take the
 * list's percentage on the base price.
 */
@Getter
@Entity
@Table(schema = "catalogue", name = "price_lists")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PriceList extends TenantEntity {

  private String name;
  private String customerType;
  private UUID customerUid;
  private String customerName;

  /** For a medicine the plan has no price for: null blocks it, 0 sells at Normal Sale, else ±%. */
  private BigDecimal adjustmentPercent;

  /** The insurer whose members' sales take this plan by default; null for none. */
  private UUID payerUid;

  private String payerName;

  private LocalDate validFrom;
  private LocalDate validTo;
  private boolean active = true;

  @OneToMany(mappedBy = "priceList", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("productUid, minQuantity")
  private List<PriceListItem> items = new ArrayList<>();

  public PriceList(String name) {
    this.name = name;
  }

  public void forPayer(UUID payerUid, String payerName) {
    this.payerUid = payerUid;
    this.payerName = payerUid == null ? null : payerName;
  }

  public void change(
      String name,
      String customerType,
      UUID customerUid,
      String customerName,
      BigDecimal adjustmentPercent,
      LocalDate validFrom,
      LocalDate validTo,
      boolean active) {
    this.name = name;
    this.customerType = customerType;
    this.customerUid = customerUid;
    this.customerName = customerName;
    this.adjustmentPercent = adjustmentPercent;
    this.validFrom = validFrom;
    this.validTo = validTo;
    this.active = active;
  }

  /**
   * Replaces the items. An item of the same product and minimum quantity is kept and repriced, not
   * deleted and inserted again: Hibernate inserts before it deletes, so re-adding it would break
   * the unique (list, product, minimum quantity) key.
   */
  public void items(List<PriceListItem> replacement) {
    java.util.Map<String, PriceListItem> wanted = new java.util.LinkedHashMap<>();
    for (PriceListItem item : replacement) {
      wanted.put(item.key(), item);
    }
    items.removeIf(item -> !wanted.containsKey(item.key()));
    for (PriceListItem item : items) {
      item.reprice(wanted.remove(item.key()).getPrice());
    }
    for (PriceListItem item : wanted.values()) {
      item.attachTo(this);
      items.add(item);
    }
  }

  public boolean validOn(LocalDate day) {
    return active
        && (validFrom == null || !day.isBefore(validFrom))
        && (validTo == null || !day.isAfter(validTo));
  }
}
