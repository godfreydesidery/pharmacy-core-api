package tz.co.pharmacy.delivery.api.v1.platform;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.modules.access.ports.inbound.Permissions;
import tz.co.pharmacy.platform.persistence.jobs.JobRuns;

/**
 * The scheduled jobs, for a root administrator (adversarial review OPS-7): per job, its latest run
 * and outcome, when it last succeeded, its latest failure with the pharmacies it failed for, and
 * the failed runs of the last 24 hours. Every instance records its runs in {@code
 * platform.job_runs}, so this is the whole cluster's view.
 */
@RestController
@RequestMapping("/api/v1/platform/jobs")
public class JobsHandler {

  private final JobRuns runs;

  public JobsHandler(JobRuns runs) {
    this.runs = runs;
  }

  @GetMapping
  @Access(scope = AccessScope.PLATFORM, permission = Permissions.PLATFORM_MANAGE)
  public ApiResponse<List<JobRuns.JobStatus>> list() {
    return ApiResponse.ok(runs.statuses(), "Scheduled jobs");
  }

  /** The latest runs of one job, newest first, skipped ones included (at most 500). */
  @GetMapping("/{job}/runs")
  @Access(scope = AccessScope.PLATFORM, permission = Permissions.PLATFORM_MANAGE)
  public ApiResponse<List<JobRuns.RunView>> runs(
      @PathVariable String job, @RequestParam(defaultValue = "50") int limit) {
    return ApiResponse.ok(runs.recent(job, limit), "Job runs");
  }
}
