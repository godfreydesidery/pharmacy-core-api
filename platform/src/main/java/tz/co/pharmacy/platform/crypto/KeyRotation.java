package tz.co.pharmacy.platform.crypto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The keys in use and the re-seal that moves every sealed value to its purpose's active key, for a
 * root administrator rotating a key ({@code /api/v1/platform/keys}). Bound in composition, which
 * knows the pharmacies to read in turn.
 */
public interface KeyRotation {

  /** The counts of values per key id, over every pharmacy and the platform. */
  Status status();

  /**
   * Starts the re-seal in the background, on one instance at a time. Returns false when it is
   * already running (here or on another instance).
   */
  boolean start();

  /**
   * @param active each purpose's active key id
   * @param keys the keys in the keyring, with the values that name them
   * @param columns each sealed column's values per key id ({@code plain}: not sealed yet)
   * @param unknownKeys values naming a key id the keyring does not hold: they cannot be opened
   * @param toReseal values not under their purpose's active key (plain ones included)
   * @param running whether this instance is running the re-seal now
   * @param lastRun this instance's last run, or null
   */
  record Status(
      Map<String, String> active,
      List<KeyUse> keys,
      List<ColumnCounts> columns,
      Map<String, Long> unknownKeys,
      long toReseal,
      boolean running,
      Run lastRun) {}

  /**
   * @param id the key id
   * @param activeFor the purposes that seal new values with it
   * @param values the stored values that name it
   * @param removable whether no value names it and no purpose seals with it: it may leave the
   *     keyring (and its secret be deleted) on the next deploy
   */
  record KeyUse(String id, List<String> activeFor, long values, boolean removable) {}

  record ColumnCounts(String column, String purpose, Map<String, Long> byKey) {}

  /**
   * @param outcome {@code running}, {@code completed}, or {@code failed: <message>}
   * @param resealed values moved to the active key
   * @param unreadable values no key in the keyring opens (left as they are)
   */
  record Run(
      Instant startedAt, Instant finishedAt, String outcome, long resealed, long unreadable) {}
}
