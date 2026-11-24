package tz.co.pharmacy.platform.approval;

/**
 * Whether the person asking may approve their own request (the owner's decision of 24 Nov 2026,
 * "owner may self-approve if alone"): only the pharmacy's owner (its Administrator), and only while
 * no one else active in the pharmacy could approve it at this branch. Implemented in composition
 * from the members and their roles; read afresh each time, so a second approver who joins ends it
 * at once on every instance.
 */
public interface SelfApproval {

  /** Whether the current actor may approve their own request in this pharmacy and branch. */
  boolean allowed();
}
