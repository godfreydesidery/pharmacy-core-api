package tz.co.pharmacy.modules.access.application;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.access.domain.User;
import tz.co.pharmacy.modules.access.persistence.UserRepository;
import tz.co.pharmacy.platform.persistence.audit.AuditLabels;

/**
 * Names users in the audit trail: sign-ins, lockouts and other security events are written about a
 * user without a label, so they read "Amina Hassan" rather than "User 8469c94c".
 */
@Component
@RequiredArgsConstructor
class UserAuditLabels implements AuditLabels {

  private final UserRepository users;

  @Override
  public String entityType() {
    return "User";
  }

  @Override
  @Transactional(readOnly = true)
  public Map<UUID, String> labels(Collection<UUID> uids) {
    Map<UUID, String> labels = new HashMap<>();
    for (User user : users.findAllByUidIn(uids)) {
      String name = user.getName();
      labels.put(user.getUid(), name == null || name.isBlank() ? user.getEmail() : name);
    }
    return labels;
  }
}
