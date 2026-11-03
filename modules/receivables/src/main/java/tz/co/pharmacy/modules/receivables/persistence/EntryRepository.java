package tz.co.pharmacy.modules.receivables.persistence;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import tz.co.pharmacy.modules.receivables.domain.Entry;
import tz.co.pharmacy.modules.receivables.ports.inbound.EntryType;

public interface EntryRepository extends JpaRepository<Entry, Long> {

  Optional<Entry> findByUid(UUID uid);

  /** An earlier charge of the same customer, sale and reference: a redelivered event's. */
  Optional<Entry> findFirstByCustomerUidAndTypeAndSaleUidAndReference(
      UUID customerUid, EntryType type, UUID saleUid, String reference);

  /** Open debits, oldest first, locked so two payments cannot settle the same charge twice. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select e from Entry e where e.customerUid = :customer and e.amount > 0"
          + " and e.openAmount > 0 order by e.entryDate, e.id")
  List<Entry> openDebits(UUID customer);

  /** Unallocated credits, oldest first, locked. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select e from Entry e where e.customerUid = :customer and e.amount < 0"
          + " and e.openAmount > 0 order by e.entryDate, e.id")
  List<Entry> openCredits(UUID customer);

  @Query(
      "select e from Entry e where e.customerUid = :customer and e.entryDate >= :from"
          + " and e.entryDate <= :to order by e.entryDate, e.id")
  List<Entry> between(UUID customer, LocalDate from, LocalDate to);

  @Query(
      "select coalesce(sum(e.amount), 0) from Entry e where e.customerUid = :customer"
          + " and e.entryDate < :before")
  BigDecimal balanceBefore(UUID customer, LocalDate before);

  @Query(
      "select coalesce(sum(e.openAmount), 0) from Entry e where e.customerUid = :customer"
          + " and e.saleUid = :sale and e.amount > 0 and e.type = tz.co.pharmacy.modules"
          + ".receivables.ports.inbound.EntryType.CHARGE")
  BigDecimal openForSale(UUID customer, UUID sale);
}
