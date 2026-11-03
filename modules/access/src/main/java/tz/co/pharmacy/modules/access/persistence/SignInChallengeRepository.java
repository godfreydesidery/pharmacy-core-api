package tz.co.pharmacy.modules.access.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.pharmacy.modules.access.domain.SignInChallenge;

public interface SignInChallengeRepository extends JpaRepository<SignInChallenge, Long> {

  Optional<SignInChallenge> findByTokenHash(String tokenHash);
}
