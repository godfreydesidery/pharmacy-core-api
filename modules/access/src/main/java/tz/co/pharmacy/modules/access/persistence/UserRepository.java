package tz.co.pharmacy.modules.access.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import tz.co.pharmacy.modules.access.domain.User;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

  Optional<User> findByEmail(String email);

  /** The account whose verified phone this is (at most one). */
  Optional<User> findByPhoneAndPhoneVerifiedAtIsNotNull(String phone);

  Optional<User> findByUid(UUID uid);

  List<User> findAllByUidIn(Collection<UUID> uids);

  @org.springframework.data.jpa.repository.Query(
      "select u from User u where lower(u.email) in :emails")
  List<User> findAllByEmails(
      @org.springframework.data.repository.query.Param("emails") Collection<String> emails);

  /**
   * Records a successful sign-in and clears the failure count, in one statement that neither checks
   * nor bumps the row's version: two sign-ins of the same person at the same moment (two devices)
   * both succeed, and a sign-in does not make someone else's open edit of the user stale.
   */
  @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
  @org.springframework.data.jpa.repository.Query(
      "update User u set u.lastSignInAt = :at, u.failedSignIns = 0, u.firstFailedAt = null,"
          + " u.lockedUntil = null where u.uid = :uid")
  int markSignedIn(
      @org.springframework.data.repository.query.Param("uid") UUID uid,
      @org.springframework.data.repository.query.Param("at") java.time.Instant at);

  boolean existsByRootAdminTrue();

  List<User> findAllByRootAdminTrueOrderByNameAsc();
}
