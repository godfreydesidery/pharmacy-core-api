package tz.co.pharmacy.platform.composition;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import tz.co.pharmacy.modules.finance.ports.inbound.Ledger;
import tz.co.pharmacy.modules.inventory.ports.inbound.StockQueries;
import tz.co.pharmacy.modules.sales.events.SaleReturnReleased;
import tz.co.pharmacy.modules.sales.events.SaleReturned;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantService;
import tz.co.pharmacy.platform.bus.EventScopes;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.kernel.Clocks;

/**
 * Keeps the ledger's inventory (1200) equal to the stock at cost where a document books stock at
 * one cost and the shelf holds it at another (MON-9). A return is booked back at what the goods
 * cost when sold, but it is restocked into a batch whose cost may have been averaged since: the
 * difference is posted as a stock adjustment (Dr 1200 / Cr 5100 when the shelf values it higher,
 * the reverse when lower), once per return.
 */
@Component
public class StockValuationReactions {

  private static final Logger LOG = LoggerFactory.getLogger(StockValuationReactions.class);
  private static final String SERVICE = "stock-valuation-posting";

  private final Ledger ledger;
  private final StockQueries stock;
  private final TenantService tenants;

  public StockValuationReactions(Ledger ledger, StockQueries stock, TenantService tenants) {
    this.ledger = ledger;
    this.stock = stock;
    this.tenants = tenants;
  }

  @Async
  @TransactionalEventListener
  public void on(SaleReturned event) {
    EventScopes.run(
        event.metadata(),
        SERVICE,
        () -> {
          BigDecimal restocked = stock.restockedValue(event.number());
          if (restocked == null) {
            return;
          }
          BigDecimal booked = Amounts.money(LedgerReactions.restockedCost(event));
          BigDecimal difference = Amounts.money(restocked.subtract(booked));
          if (difference.signum() == 0) {
            return;
          }
          try {
            ledger.postStockAdjustment(
                event.returnUid(),
                "Return " + event.number() + " restocked at the batch's current cost",
                event.branchUid(),
                localDate(event.metadata().tenantUid(), event.metadata().occurredAt()),
                difference);
          } catch (DataIntegrityViolationException alreadyPosted) {
            LOG.info(
                "Journal entry already posted: {}",
                alreadyPosted.getMostSpecificCause().getMessage());
          }
        });
  }

  /**
   * Units released from quarantine come back at their batch's cost of today, which may differ from
   * the cost they were sold at (booked back by {@code LedgerReactions}): the difference is a stock
   * adjustment, once per return line.
   */
  @Async
  @TransactionalEventListener
  public void on(SaleReturnReleased event) {
    EventScopes.run(
        event.metadata(),
        SERVICE,
        () -> {
          BigDecimal restocked = stock.restockedValue(event.reference());
          if (restocked == null) {
            return;
          }
          BigDecimal difference = Amounts.money(restocked.subtract(event.cost()));
          if (difference.signum() == 0) {
            return;
          }
          try {
            ledger.postStockAdjustment(
                UUID.nameUUIDFromBytes(
                    ("release-valuation/" + event.lineUid())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "Return " + event.reference() + " released at the batch's current cost",
                event.branchUid(),
                localDate(event.metadata().tenantUid(), event.metadata().occurredAt()),
                difference);
          } catch (DataIntegrityViolationException alreadyPosted) {
            LOG.info(
                "Journal entry already posted: {}",
                alreadyPosted.getMostSpecificCause().getMessage());
          }
        });
  }

  private LocalDate localDate(UUID tenantUid, Instant occurredAt) {
    ZoneId zone =
        tenants.find(tenantUid).map(tenant -> ZoneId.of(tenant.timezone())).orElse(ZoneOffset.UTC);
    return LocalDate.ofInstant(occurredAt == null ? Clocks.now() : occurredAt, zone);
  }
}
