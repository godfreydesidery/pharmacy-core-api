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

  /**
   * Instead of {@link #permission()}: the caller must hold at least one of these, for a read that
   * serves two screens (the till's view of a customer's credit with {@code sales.sell}, the
   * accounts page with {@code finance.view}). Empty means only {@link #permission()} applies.
   */
  String[] anyOf() default {};

  /**
   * Besides {@link #permission()}: the caller must hold every one of these, such as a list's view
   * permission and {@code data.export} for the bulk export of people's data in that list.
   */
  String[] allOf() default {};

  /**
   * A sensitive change to the caller's own account (passkeys, two-factor, phone): the session must
   * have proved its password within the last few minutes ({@code pharmacy.access.step-up-minutes},
   * 10 by default), else 403 {@code reauthentication_required}; the app then asks for the password
   * and calls {@code POST /auth/confirm-identity} (OWASP ASVS V6/V7 re-authentication, "sudo
   * mode").
   */
  boolean stepUp() default false;
}
