package tz.co.pharmacy.modules.access.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.pharmacy.modules.access.domain.Session;
import tz.co.pharmacy.modules.access.domain.User;
import tz.co.pharmacy.modules.access.persistence.MembershipRepository;
import tz.co.pharmacy.modules.access.persistence.SessionRepository;
import tz.co.pharmacy.modules.access.persistence.UserRepository;
import tz.co.pharmacy.modules.access.ports.inbound.PharmacyVisits;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.persistence.TenantContext;
import tz.co.pharmacy.platform.persistence.TenantSwitch;
import tz.co.pharmacy.platform.persistence.audit.AuditLog;

@Service
class PharmacyVisitsImpl implements PharmacyVisits {

  /** How often a member's last activity is written, at most, per session and pharmacy. */
  static final Duration EVERY = Duration.ofMinutes(5);

  private static final Logger log = LoggerFactory.getLogger(PharmacyVisitsImpl.class);

  private final SessionRepository sessions;
  private final UserRepository users;
  private final MembershipRepository memberships;
  private final TenantSwitch tenantSwitch;
  private final AuditLog audit;
  private final TransactionTemplate writes;
  private final Clock clock;

  /** When each session last noted work in each pharmacy, so most requests write nothing. */
  private final Map<String, Instant> noted = new ConcurrentHashMap<>();

  PharmacyVisitsImpl(
      SessionRepository sessions,
      UserRepository users,
      MembershipRepository memberships,
      TenantSwitch tenantSwitch,
      AuditLog audit,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.sessions = sessions;
    this.users = users;
    this.memberships = memberships;
    this.tenantSwitch = tenantSwitch;
    this.audit = audit;
    this.writes = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  @Override
  public void working(UUID sessionUid, UUID userUid, UUID tenantUid) {
    if (sessionUid == null || userUid == null || tenantUid == null) {
      return;
    }
    Instant now = clock.instant();
    String key = sessionUid + "/" + tenantUid;
    Instant last = noted.get(key);
    if (last != null && last.plus(EVERY).isAfter(now)) {
      return;
    }
    noted.put(key, now);
    if (noted.size() > 50_000) {
      // Sessions come and go; an old note only costs one extra write.
      noted.clear();
      noted.put(key, now);
    }
    try {
      note(sessionUid, userUid, tenantUid, now);
    } catch (RuntimeException failure) {
      // Bookkeeping: the request itself goes on.
      noted.remove(key);
      log.warn("Could not note work of session {} in tenant {}", sessionUid, tenantUid, failure);
    }
  }

  private void note(UUID sessionUid, UUID userUid, UUID tenantUid, Instant now) {
    User user = users.findByUid(userUid).orElse(null);
    if (user == null) {
      return;
    }
    ExecutionContext.callAs(
        SessionIssuer.userFrame(user),
        () ->
            TenantContext.callAs(
                null,
                () ->
                    writes.execute(
                        status -> {
                          boolean first = sessions.enter(sessionUid, tenantUid, now) == 1;
                          Session session =
                              first ? sessions.findByUid(sessionUid).orElse(null) : null;
                          tenantSwitch.within(
                              tenantUid,
                              () -> {
                                memberships
                                    .findByUserUidAndTenantUid(userUid, tenantUid)
                                    .ifPresent(membership -> membership.workedAt(now));
                                if (first) {
                                  Map<String, Object> details = new LinkedHashMap<>();
                                  if (session != null) {
                                    details.put("method", session.getMethod());
                                    details.put("ip", session.getIp());
                                    details.put(
                                        "userAgent",
                                        SessionIssuer.trim(session.getUserAgent(), 200));
                                  }
                                  audit.record("SIGN_IN", "User", userUid, null, details);
                                }
                                return null;
                              });
                          return null;
                        })));
  }
}
