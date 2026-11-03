package tz.co.pharmacy.delivery.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Every handler method declares who may call it; the edge refuses a handler method without one
 * (fail closed). {@code permission} is a code of the permission catalogue, such as {@code
 * sales.sell}; empty means any signed-in caller in scope. A root administrator holds every
 * permission.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Access {

  AccessScope scope();

  String permission() default "";
}
