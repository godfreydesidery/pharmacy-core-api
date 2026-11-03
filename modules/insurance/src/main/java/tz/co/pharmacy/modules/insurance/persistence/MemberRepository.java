package tz.co.pharmacy.modules.insurance.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import tz.co.pharmacy.modules.insurance.domain.Member;

/** Row-level security limits every query to the current tenant. */
public interface MemberRepository
    extends JpaRepository<Member, Long>, JpaSpecificationExecutor<Member> {

  Optional<Member> findByUid(UUID uid);

  List<Member> findByCustomerUidOrderByIdDesc(UUID customerUid);

  Optional<Member> findBySchemeUidAndMemberNumberIgnoreCase(UUID schemeUid, String memberNumber);
}
