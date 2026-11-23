package tz.co.pharmacy.platform.composition;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.platform.persistence.SingleRunner;

/**
 * Delivers again the events whose listener failed (adversarial review MON-13): a ledger posting, a
 * read model or a webhook that threw stays an incomplete publication in the event registry, and was
 * before retried only when the application restarted. Every few minutes, publications older than
 * {@code pharmacy.events.resubmit-older-than} (5 minutes) are handed to their listeners again with
 * Spring Modulith's {@link IncompleteEventPublications}. The listeners are idempotent per source
 * document (the journal posts each source once), so a second delivery adds nothing. Only one
 * instance runs it at a time ({@link SingleRunner}, a PostgreSQL advisory lock), which also records
 * each run and its failure.
 */
@Component
class EventResubmissionSchedule {

  private static final String JOB = "event-resubmission";

  private final IncompleteEventPublications incomplete;
  private final SingleRunner runner;
  private final Duration olderThan;

  EventResubmissionSchedule(
      IncompleteEventPublications incomplete,
      SingleRunner runner,
      @Value("${pharmacy.events.resubmit-older-than:PT5M}") Duration olderThan) {
    this.incomplete = incomplete;
    this.runner = runner;
    this.olderThan = olderThan;
  }

  @Scheduled(
      fixedDelayString = "${pharmacy.events.resubmit-every:PT5M}",
      initialDelayString = "${pharmacy.events.resubmit-every:PT5M}")
  void resubmit() {
    runner.runAlone(JOB, run -> incomplete.resubmitIncompletePublicationsOlderThan(olderThan));
  }
}
