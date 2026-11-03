package tz.co.pharmacy.modules.access.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.pharmacy.modules.access.domain.PasswordReset;

public interface PasswordResetRepository extends JpaRepository<PasswordReset, Long> {

  Optional<PasswordReset> findByTokenHash(String tokenHash);
}
