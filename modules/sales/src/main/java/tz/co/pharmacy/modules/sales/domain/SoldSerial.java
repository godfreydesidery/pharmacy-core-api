package tz.co.pharmacy.modules.sales.domain;

import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One serial-tracked unit sold on a sale line, such as wheelchair WCH-0001, and whether it came
 * back.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SoldSerial {

  private String serialNumber;
  private boolean returned;

  public SoldSerial(String serialNumber) {
    this.serialNumber = serialNumber;
  }

  void markReturned() {
    this.returned = true;
  }
}
