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

  Optional<User> findByUid(UUID uid);

  List<User> findAllByUidIn(Collection<UUID> uids);

  @org.springframework.data.jpa.repository.Query(
      "select u from User u where lower(u.email) in :emails")
  List<User> findAllByEmails(
      @org.springframework.data.repository.query.Param("emails") Collection<String> emails);

  boolean existsByRootAdminTrue();

  List<User> findAllByRootAdminTrueOrderByNameAsc();
}
