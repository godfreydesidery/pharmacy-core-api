package tz.co.pharmacy.modules.finance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.modules.finance.ports.inbound.AccountType;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/** An account of the tenant's chart, keyed by its code such as 1000 Cash. */
@Getter
@Entity
@Table(schema = "finance", name = "accounts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account extends TenantEntity {

  @Column(nullable = false)
  private String code;

  @Column(nullable = false)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private AccountType type;
}
