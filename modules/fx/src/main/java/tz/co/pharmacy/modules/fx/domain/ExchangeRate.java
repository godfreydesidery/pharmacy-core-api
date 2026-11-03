package tz.co.pharmacy.modules.fx.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/** Units of {@code quote} per 1 {@code base} from {@code effectiveOn}, from one source. */
@Getter
@Entity
@Table(schema = "fx", name = "exchange_rates")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExchangeRate extends TenantEntity {

  private String base;
  private String quote;

  @Column(precision = 24, scale = 10)
  private BigDecimal rate;

  private LocalDate effectiveOn;
  private String source;

  public ExchangeRate(
      String base, String quote, BigDecimal rate, LocalDate effectiveOn, String source) {
    this.base = base;
    this.quote = quote;
    this.rate = rate;
    this.effectiveOn = effectiveOn;
    this.source = source;
  }

  public void replaceRate(BigDecimal rate) {
    this.rate = rate;
  }
}
