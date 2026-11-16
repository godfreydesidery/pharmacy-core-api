package tz.co.pharmacy.platform.persistence.audit;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Names for the records of one entity type, for audit entries written without a label (security
 * events such as SIGN_IN carry no name of their own). The module that owns the records provides it;
 * entries are labelled as they are read, so older entries read well too.
 */
public interface AuditLabels {

  /** The entity type this names, as written in audit entries (e.g. "User"). */
  String entityType();

  /** A human label per uid, for the uids it knows; others are left out. */
  Map<UUID, String> labels(Collection<UUID> uids);
}
