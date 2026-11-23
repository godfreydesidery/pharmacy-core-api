package tz.co.pharmacy.delivery.api.v1.admin;

import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.modules.access.ports.inbound.Permissions;
import tz.co.pharmacy.modules.files.ports.inbound.Archives;
import tz.co.pharmacy.modules.files.ports.inbound.DownloadLink;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantData;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantStatus;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.persistence.TenantContext;
import tz.co.pharmacy.platform.persistence.exports.DataExportJobs;

/**
 * The pharmacy takes its data out: every table as CSV, its people and its files, in one ZIP. Open
 * to its administrators even while the account is unpaid or closed (the edge lets them read, and
 * start an export).
 *
 * <p>The export is a background job: {@code POST} starts it (202, one at a time per pharmacy, 409
 * export_running), {@code GET /{uid}} tells how it is going and, once READY, gives a signed
 * download link valid {@code pharmacy.exports.link-ttl} (15 minutes); ask again for a fresh one.
 * The file is kept {@code pharmacy.exports.keep} (7 days). The old synchronous {@code GET} stays
 * while the web app moves over; it is built the same way, then sent.
 */
@RestController
@RequestMapping("/api/v1/data-export")
public class DataExportHandler {

  private final TenantData data;
  private final TenantService tenants;
  private final DataExportJobs jobs;
  private final Archives archives;
  private final Clock clock;
  private final Duration linkTtl;

  public DataExportHandler(
      TenantData data,
      TenantService tenants,
      DataExportJobs jobs,
      Archives archives,
      Clock clock,
      @Value("${pharmacy.exports.link-ttl:PT15M}") Duration linkTtl) {
    this.data = data;
    this.tenants = tenants;
    this.jobs = jobs;
    this.archives = archives;
    this.clock = clock;
    this.linkTtl = linkTtl;
  }

  /** A full export, as the job's state and, once READY, its download link. */
  public record DataExportView(
      UUID uid,
      String status,
      Instant requestedAt,
      String requestedBy,
      Instant startedAt,
      Instant finishedAt,
      /* Until when the file is kept (READY). */
      Instant expiresAt,
      Long sizeBytes,
      /* A signed GET of the ZIP (READY only), valid until downloadExpiresAt. */
      String downloadUrl,
      Instant downloadExpiresAt,
      /* FAILED only: a sentence for the person (the cause stays in the server's log). */
      String error) {}

  @PostMapping
  @ResponseStatus(HttpStatus.ACCEPTED)
  @Access(scope = AccessScope.TENANT, permission = Permissions.SETTINGS_MANAGE)
  public ApiResponse<DataExportView> start() {
    UUID tenantUid = TenantContext.requireTenant();
    tenants
        .find(tenantUid)
        .filter(tenant -> tenant.status() == TenantStatus.ERASED)
        .ifPresent(
            erased -> {
              throw Refusal.invalid("erased", "The pharmacy's data is already erased.", "status");
            });
    DataExportJobs.Job job = jobs.start(tenantUid, DataExportJobs.Mode.STORED);
    return ApiResponse.ok(view(job, null), "Export started");
  }

  @GetMapping("/{uid}")
  @Access(scope = AccessScope.TENANT, permission = Permissions.SETTINGS_MANAGE)
  public ApiResponse<DataExportView> status(@PathVariable UUID uid) {
    UUID tenantUid = TenantContext.requireTenant();
    DataExportJobs.Job job =
        jobs.find(tenantUid, uid)
            .filter(found -> found.mode() == DataExportJobs.Mode.STORED)
            .orElseThrow(() -> Refusal.notFound("export_not_found", "No such export."));
    DownloadLink link =
        "READY".equals(job.status()) && job.fileUid() != null
            ? archives.link(job.fileUid(), linkTtl)
            : null;
    return ApiResponse.ok(view(job, link), "Export " + job.status().toLowerCase());
  }

  /**
   * The old synchronous download, kept while the web app moves to {@code POST} and polling. Built
   * the same way (short transactions, one export at a time), then sent.
   */
  @GetMapping
  @Access(scope = AccessScope.TENANT, permission = Permissions.SETTINGS_MANAGE)
  public void export(HttpServletResponse response) {
    UUID tenantUid = TenantContext.requireTenant();
    DataExports.write(data, tenants, clock, tenantUid, response);
  }

  private static DataExportView view(DataExportJobs.Job job, DownloadLink link) {
    return new DataExportView(
        job.uid(),
        job.status(),
        job.requestedAt(),
        job.requestedBy(),
        job.startedAt(),
        job.finishedAt(),
        job.expiresAt(),
        job.sizeBytes(),
        link == null ? null : link.url(),
        link == null ? null : link.expiresAt(),
        "FAILED".equals(job.status())
            ? "The export could not be prepared; start a new one."
            : null);
  }
}
