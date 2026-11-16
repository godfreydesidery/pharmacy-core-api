package tz.co.pharmacy.modules.tenancy.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.modules.tenancy.ports.inbound.BillingCycle;
import tz.co.pharmacy.modules.tenancy.ports.inbound.PlanFeature;
import tz.co.pharmacy.modules.tenancy.ports.inbound.PlanLimit;
import tz.co.pharmacy.platform.persistence.MutableEntity;

/** A subscription plan: its prices, caps (null for unlimited) and the modules it includes. */
@Getter
@Entity
@Table(schema = "tenancy", name = "plans")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Plan extends MutableEntity {

  private String code;
  private String name;
  private String description;
  private String currency;
  private BigDecimal priceMonthly;
  private BigDecimal priceYearly;
  private Integer maxBranches;
  private Integer maxUsers;
  private Integer maxProducts;
  private Integer smsPerMonth;
  private boolean insurance;
  private boolean multiCurrency;
  private boolean integrations;
  private boolean advancedReports;
  private int trialDays;
  private int sortOrder;
  private boolean active = true;

  public Plan(String code) {
    this.code = code;
  }

  public void change(
      String name,
      String description,
      String currency,
      BigDecimal priceMonthly,
      BigDecimal priceYearly,
      Integer maxBranches,
      Integer maxUsers,
      Integer maxProducts,
      Integer smsPerMonth,
      Set<PlanFeature> features,
      int trialDays,
      int sortOrder,
      boolean active) {
    this.name = name;
    this.description = description;
    this.currency = currency;
    this.priceMonthly = priceMonthly;
    this.priceYearly = priceYearly;
    this.maxBranches = maxBranches;
    this.maxUsers = maxUsers;
    this.maxProducts = maxProducts;
    this.smsPerMonth = smsPerMonth;
    this.insurance = features.contains(PlanFeature.INSURANCE);
    this.multiCurrency = features.contains(PlanFeature.MULTI_CURRENCY);
    this.integrations = features.contains(PlanFeature.INTEGRATIONS);
    this.advancedReports = features.contains(PlanFeature.ADVANCED_REPORTS);
    this.trialDays = trialDays;
    this.sortOrder = sortOrder;
    this.active = active;
  }

  public Set<PlanFeature> features() {
    Set<PlanFeature> on = EnumSet.noneOf(PlanFeature.class);
    if (insurance) on.add(PlanFeature.INSURANCE);
    if (multiCurrency) on.add(PlanFeature.MULTI_CURRENCY);
    if (integrations) on.add(PlanFeature.INTEGRATIONS);
    if (advancedReports) on.add(PlanFeature.ADVANCED_REPORTS);
    return on;
  }

  /** The cap of a quantity, null for unlimited. */
  public Integer cap(PlanLimit limit) {
    return switch (limit) {
      case BRANCHES -> maxBranches;
      case USERS -> maxUsers;
      case PRODUCTS -> maxProducts;
      case SMS -> smsPerMonth;
    };
  }

  public BigDecimal price(BillingCycle cycle) {
    return cycle == BillingCycle.YEARLY ? priceYearly : priceMonthly;
  }
}
