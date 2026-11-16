package tz.co.pharmacy.modules.catalogue.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/** A pack product that opens into {@code unitsPerPack} of a unit product. */
@Getter
@Entity
@Table(schema = "catalogue", name = "pack_links")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PackLink extends TenantEntity {

  private UUID packProductUid;
  private UUID unitProductUid;
  private int unitsPerPack;

  public PackLink(UUID packProductUid, UUID unitProductUid, int unitsPerPack) {
    change(packProductUid, unitsPerPack);
    this.unitProductUid = unitProductUid;
  }

  public void change(UUID packProductUid, int unitsPerPack) {
    this.packProductUid = packProductUid;
    this.unitsPerPack = unitsPerPack;
  }
}
