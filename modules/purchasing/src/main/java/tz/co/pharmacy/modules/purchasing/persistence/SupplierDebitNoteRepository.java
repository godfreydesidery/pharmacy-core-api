package tz.co.pharmacy.modules.purchasing.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.pharmacy.modules.purchasing.domain.SupplierDebitNote;

public interface SupplierDebitNoteRepository extends JpaRepository<SupplierDebitNote, Long> {

  List<SupplierDebitNote> findByGoodsReceiptUidOrderByIdAsc(UUID goodsReceiptUid);
}
