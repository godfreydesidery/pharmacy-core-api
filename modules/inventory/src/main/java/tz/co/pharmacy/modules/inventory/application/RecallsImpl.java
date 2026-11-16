package tz.co.pharmacy.modules.inventory.application;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.inventory.domain.Batch;
import tz.co.pharmacy.modules.inventory.domain.ProductRecord;
import tz.co.pharmacy.modules.inventory.persistence.BatchRepository;
import tz.co.pharmacy.modules.inventory.persistence.ProductRecordRepository;
import tz.co.pharmacy.modules.inventory.persistence.StockLevelRepository;
import tz.co.pharmacy.modules.inventory.ports.inbound.Recalls;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Refusal;

@Service
@RequiredArgsConstructor
class RecallsImpl implements Recalls {

  private final BatchRepository batches;
  private final StockLevelRepository levels;
  private final ProductRecordRepository products;
  private final Clock clock;

  @Override
  @Transactional(readOnly = true)
  public List<RecallBatch> batches(UUID productUid) {
    Checks.required(productUid, "productUid");
    String name = products.findByProductUid(productUid).map(ProductRecord::getName).orElse(null);
    return batches.findByProductUidOrderByExpiryDateDescIdDesc(productUid).stream()
        .map(batch -> view(batch, name))
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public RecallBatch batch(UUID batchUid) {
    return view(find(batchUid));
  }

  @Override
  @Transactional
  public RecallBatch quarantine(UUID batchUid, String reason) {
    Batch batch = find(batchUid);
    batch.quarantine(
        Checks.text(reason, "reason", 500), clock.instant(), ExecutionContext.actor().reference());
    return view(batch);
  }

  @Override
  @Transactional
  public RecallBatch release(UUID batchUid) {
    Batch batch = find(batchUid);
    batch.release();
    return view(batch);
  }

  private Batch find(UUID batchUid) {
    return batches
        .findByUid(Checks.required(batchUid, "batchUid"))
        .orElseThrow(() -> Refusal.notFound("batch_not_found", "No such batch."));
  }

  private RecallBatch view(Batch batch) {
    return view(
        batch,
        products.findByProductUid(batch.getProductUid()).map(ProductRecord::getName).orElse(null));
  }

  private RecallBatch view(Batch batch, String productName) {
    return new RecallBatch(
        batch.getUid(),
        batch.getProductUid(),
        productName,
        batch.getBatchNumber(),
        batch.getExpiryDate(),
        levels.quantityOfBatch(batch),
        batch.isQuarantined(),
        batch.getQuarantineReason(),
        batch.getQuarantinedAt(),
        batch.getQuarantinedBy());
  }
}
