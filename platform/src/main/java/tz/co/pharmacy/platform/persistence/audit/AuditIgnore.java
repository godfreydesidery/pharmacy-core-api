package tz.co.pharmacy.platform.persistence.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Leaves an entity's changes out of the automatic audit trail, for short-lived technical rows such
 * as sign-in challenges, whose security-relevant moments are recorded explicitly instead.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface AuditIgnore {}
