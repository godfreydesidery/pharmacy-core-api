package tz.co.pharmacy.modules.finance.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import tz.co.pharmacy.modules.finance.domain.JournalEntry;

public interface JournalEntryRepository
    extends JpaRepository<JournalEntry, Long>, JpaSpecificationExecutor<JournalEntry> {

  boolean existsBySourceTypeAndSourceUid(String sourceType, UUID sourceUid);
}
