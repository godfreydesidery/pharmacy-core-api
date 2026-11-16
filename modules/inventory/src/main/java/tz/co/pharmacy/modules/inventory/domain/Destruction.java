package tz.co.pharmacy.modules.inventory.domain;

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
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/** Stock destroyed at one branch before a witness: the register entry and its certificate. */
@Getter
@Entity
@Table(schema = "inventory", name = "destructions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Destruction extends TenantEntity {

  private String number;
  private UUID branchUid;
  private LocalDate destroyedOn;
  private String method;
  private String witnessName;
  private String witnessTitle;
  private String certificateNumber;
  private String note;
  private BigDecimal totalCost = BigDecimal.ZERO;

  @OneToMany(mappedBy = "destruction", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("id")
  private List<DestructionLine> lines = new ArrayList<>();

  public Destruction(
      String number,
      UUID branchUid,
      LocalDate destroyedOn,
      String method,
      String witnessName,
      String witnessTitle,
      String certificateNumber,
      String note) {
    this.number = number;
    this.branchUid = branchUid;
    this.destroyedOn = destroyedOn;
    this.method = method;
    this.witnessName = witnessName;
    this.witnessTitle = witnessTitle;
    this.certificateNumber = certificateNumber;
    this.note = note;
  }

  public void addLine(DestructionLine line) {
    line.attachTo(this);
    lines.add(line);
    totalCost =
        Amounts.money(
            totalCost.add(line.getUnitCost().multiply(BigDecimal.valueOf(line.getQuantity()))));
  }
}
