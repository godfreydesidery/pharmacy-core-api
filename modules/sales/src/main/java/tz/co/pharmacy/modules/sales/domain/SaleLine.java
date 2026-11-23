package tz.co.pharmacy.modules.sales.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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

/** One batch of one product on a sale, priced. */
@Entity
@Table(name = "sale_lines", schema = "sales")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SaleLine extends TenantEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "sale_id", nullable = false)
  private Sale sale;

  @Column(nullable = false)
  private UUID productUid;

  @Column(nullable = false)
  private String productName;

  @Column(nullable = false)
  private UUID batchUid;

  private String batchNumber;
  private LocalDate expiryDate;

  @Column(nullable = false)
  private int quantity;

  @Column(nullable = false)
  private int returnedQuantity;

  @Column(nullable = false, precision = 14, scale = 2)
  private BigDecimal unitPrice;

  @Column(nullable = false, precision = 14, scale = 2)
  private BigDecimal unitCost;

  @Column(nullable = false, precision = 5, scale = 2)
  private BigDecimal taxRatePercent;

  @Column(nullable = false, precision = 14, scale = 2)
  private BigDecimal discount;

  @Column(nullable = false, precision = 14, scale = 2)
  private BigDecimal tax;

  @Column(nullable = false, precision = 14, scale = 2)
  private BigDecimal lineTotal;

  /** The last day of the warranty of what the line sold, or null without one. */
  private LocalDate warrantyEndsOn;

  /** The medicine's dispensing class when sold; null on lines sold before it was kept. */
  private String schedule;

  /** The promotion that priced the line, and its name then; null without one. */
  private UUID promotionUid;

  private String promotionName;

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(
      name = "sale_line_serials",
      schema = "sales",
      joinColumns = @JoinColumn(name = "sale_line_id"))
  private List<SoldSerial> serials = new ArrayList<>();

  public SaleLine(
      UUID productUid,
      String productName,
      UUID batchUid,
      String batchNumber,
      LocalDate expiryDate,
      int quantity,
      BigDecimal unitPrice,
      BigDecimal unitCost,
      BigDecimal taxRatePercent,
      SaleTotals.LineAmounts amounts) {
    this.productUid = productUid;
    this.productName = productName;
    this.batchUid = batchUid;
    this.batchNumber = batchNumber;
    this.expiryDate = expiryDate;
    this.quantity = quantity;
    this.unitPrice = unitPrice;
    this.unitCost = unitCost;
    this.taxRatePercent = taxRatePercent;
    this.discount = amounts.discount();
    this.tax = amounts.tax();
    this.lineTotal = amounts.lineTotal();
  }

  void attachTo(Sale sale) {
    this.sale = sale;
  }

  /** Records the serial-tracked units sold on the line and its warranty end. */
  public void sold(List<String> serialNumbers, LocalDate warrantyEndsOn) {
    if (serialNumbers != null) {
      serialNumbers.forEach(serial -> serials.add(new SoldSerial(serial)));
    }
    this.warrantyEndsOn = warrantyEndsOn;
  }

  /** Keeps the medicine's class at the moment of sale and the promotion that priced the line. */
  public void classified(String schedule, UUID promotionUid, String promotionName) {
    this.schedule = schedule;
    this.promotionUid = promotionUid;
    this.promotionName = promotionName;
  }

  public List<String> serialNumbers() {
    return serials.stream().map(SoldSerial::getSerialNumber).toList();
  }

  public List<String> returnedSerialNumbers() {
    return serials.stream()
        .filter(SoldSerial::isReturned)
        .map(SoldSerial::getSerialNumber)
        .toList();
  }

  /**
   * Marks {@code units} serials returned: the named ones, which must be sold on this line and not
   * yet back, or else the first not yet back. Returns the serial numbers returned; empty for a line
   * without serials.
   */
  public List<String> returnSerials(List<String> named, int units) {
    if (serials.isEmpty()) {
      return List.of();
    }
    List<SoldSerial> chosen = new ArrayList<>();
    if (named == null || named.isEmpty()) {
      serials.stream().filter(serial -> !serial.isReturned()).limit(units).forEach(chosen::add);
    } else {
      for (String number : named) {
        SoldSerial serial =
            serials.stream()
                .filter(candidate -> candidate.getSerialNumber().equals(number.trim()))
                .filter(candidate -> !candidate.isReturned())
                .findFirst()
                .orElse(null);
        if (serial == null || chosen.contains(serial)) {
          return null;
        }
        chosen.add(serial);
      }
    }
    if (chosen.size() != units) {
      return null;
    }
    chosen.forEach(SoldSerial::markReturned);
    return chosen.stream().map(SoldSerial::getSerialNumber).toList();
  }

  /** The units that may still come back. */
  public int returnable() {
    return quantity - returnedQuantity;
  }

  public void returned(int units) {
    this.returnedQuantity += units;
  }
}
