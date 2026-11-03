/**
 * The HTTP edge and the v1 handlers. It owns no data and reaches modules only through their inbound
 * ports and the application services.
 */
@ApplicationModule(displayName = "delivery")
package tz.co.pharmacy.delivery;

import org.springframework.modulith.ApplicationModule;
