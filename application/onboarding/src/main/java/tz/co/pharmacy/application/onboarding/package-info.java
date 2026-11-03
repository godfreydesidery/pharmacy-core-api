/**
 * The onboarding application service: registering a tenant writes the tenant registry, the new
 * tenant's branch and settings (tenancy), and its roles and first administrator (access), in one
 * transaction. It decides nothing those modules own.
 */
@ApplicationModule(displayName = "onboarding")
package tz.co.pharmacy.application.onboarding;

import org.springframework.modulith.ApplicationModule;
