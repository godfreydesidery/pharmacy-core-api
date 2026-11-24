package tz.co.pharmacy.modules.sales.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tz.co.pharmacy.modules.sales.domain.SaleDebitNote;

public interface SaleDebitNoteRepository extends JpaRepository<SaleDebitNote, Long> {

  List<SaleDebitNote> findBySaleUidOrderByIdAsc(UUID saleUid);

  Optional<SaleDebitNote> findByUid(UUID uid);

  /** The fiscal document's status and, once issued, its number beside the debit note. */
  @Modifying
  @Query(
      value =
          "update sales.debit_notes set fiscal_status = :status,"
              + " fiscal_reference = coalesce(cast(:reference as text), fiscal_reference)"
              + " where uid = :uid",
      nativeQuery = true)
  int recordFiscal(
      @Param("uid") UUID uid, @Param("status") String status, @Param("reference") String reference);
}
