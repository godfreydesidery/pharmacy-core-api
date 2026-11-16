package tz.co.pharmacy.modules.inventory.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/** A stock-take at one branch: the expected quantity of each batch and what was counted. */
@Getter
@Entity
@Table(schema = "inventory", name = "stock_counts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockCount extends TenantEntity {

  private String number;
  private UUID branchUid;
  private String status;
  private String note;
  private Instant postedAt;
  private String postedBy;

  @OneToMany(mappedBy = "stockCount", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("productName, expiryDate, batchNumber")
  private List<StockCountLine> lines = new ArrayList<>();

  public StockCount(String number, UUID branchUid, String note) {
    this.number = number;
    this.branchUid = branchUid;
    this.note = note;
    this.status = "OPEN";
  }

  public void addLine(StockCountLine line) {
    line.attachTo(this);
    lines.add(line);
  }

  public void posted(Instant at, String by) {
    this.status = "POSTED";
    this.postedAt = at;
    this.postedBy = by;
  }
}
