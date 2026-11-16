package tz.co.pharmacy.delivery.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import tz.co.pharmacy.modules.tenancy.ports.inbound.PlanFeature;

/**
 * The handler (or every handler of the class) needs a module of the tenant's subscription plan; the
 * edge refuses 403 plan_feature_unavailable otherwise. A root administrator is never refused.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequiresFeature {

  PlanFeature value();
}
