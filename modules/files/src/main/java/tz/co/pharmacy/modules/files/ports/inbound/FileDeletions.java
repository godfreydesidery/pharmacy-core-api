package tz.co.pharmacy.modules.files.ports.inbound;

/**
 * Deleting objects from the store for good, with a retry list: an object the store cannot delete
 * now (it is down, or refuses) is kept in {@code files.pending_deletes} and tried again by {@link
 * #retryDue}, which the nightly retention calls, instead of being forgotten. Platform scope: the
 * keys may belong to a pharmacy already erased.
 */
public interface FileDeletions {

  /**
   * Deletes the object at {@code storageKey}; returns true when it is gone now, false when it was
   * queued for a retry ({@code reason} says why it was being deleted, such as "erasure").
   */
  boolean delete(String storageKey, String reason);

  /**
   * Tries again up to {@code limit} queued deletes that are due, each claimed by one instance only
   * (FOR UPDATE SKIP LOCKED); a failure waits longer each time (1 hour, doubling, at most a day).
   */
  RetryOutcome retryDue(int limit);

  /** How many queued deletes succeeded and how many failed again. */
  record RetryOutcome(long deleted, long failed) {}
}
