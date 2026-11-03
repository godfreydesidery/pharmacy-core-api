package tz.co.pharmacy.modules.receivables.persistence;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import tz.co.pharmacy.modules.receivables.domain.Allocation;

public interface AllocationRepository extends JpaRepository<Allocation, Long> {

  @Query(
      "select a from Allocation a join fetch a.credit join fetch a.debit"
          + " where a.credit.id in :entryIds or a.debit.id in :entryIds")
  List<Allocation> touching(Collection<Long> entryIds);
}
