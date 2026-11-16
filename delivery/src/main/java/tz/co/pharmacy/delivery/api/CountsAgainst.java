package tz.co.pharmacy.delivery.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import tz.co.pharmacy.modules.tenancy.ports.inbound.PlanLimit;

/**
 * The handler adds one of a quantity the subscription plan caps (a branch, a user, a product); the
 * edge refuses 422 plan_limit_reached when the cap is reached. A root administrator is never
 * refused.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface CountsAgainst {

  PlanLimit value();
}
