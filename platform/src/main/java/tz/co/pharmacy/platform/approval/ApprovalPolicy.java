package tz.co.pharmacy.platform.approval;

import java.math.BigDecimal;

/**
 * When an action needs a second person's approval, by the pharmacy's own rules. Implemented in
 * composition from the pharmacy's settings (its approval limit) and the actions that always need
 * one (writing off a controlled drug, moving the books' lock back, changing a medicine's dispensing
 * class, writing off a customer's debt, a late return…).
 */
public interface ApprovalPolicy {

  /**
   * The amount above which {@code action} needs approval: {@code null} never, {@link
   * BigDecimal#ZERO} always, otherwise above that amount (in the base currency).
   */
  BigDecimal limitFor(String action);
}
