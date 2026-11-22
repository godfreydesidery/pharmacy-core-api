package tz.co.pharmacy.delivery.api.v1.admin;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantData;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantView;
import tz.co.pharmacy.platform.kernel.Refusal;

/** A pharmacy's data export as the HTTP response, for its admins and the root console alike. */
public final class DataExports {

  private DataExports() {}

  /** Streams one pharmacy's export as a ZIP attachment named after its code and the day. */
  public static void write(
      TenantData data,
      TenantService tenants,
      Clock clock,
      UUID tenantUid,
      HttpServletResponse response) {
    TenantView tenant =
        tenants
            .find(tenantUid)
            .orElseThrow(() -> Refusal.notFound("tenant_not_found", "No such tenant."));
    String name =
        tenant.code().replaceAll("[^A-Za-z0-9_-]", "_")
            + "-export-"
            + LocalDate.ofInstant(clock.instant(), java.time.ZoneOffset.UTC)
            + ".zip";
    response.setContentType("application/zip");
    response.setHeader("Content-Disposition", "attachment; filename=\"" + name + "\"");
    response.setHeader("Cache-Control", "no-store");
    try {
      data.export(tenantUid, response.getOutputStream());
      response.flushBuffer();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
