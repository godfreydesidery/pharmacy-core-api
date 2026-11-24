package tz.co.pharmacy.platform.composition;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.catalogue.ports.inbound.CatalogueService;
import tz.co.pharmacy.modules.catalogue.ports.inbound.ProductView;
import tz.co.pharmacy.modules.partners.ports.inbound.SupplierService;
import tz.co.pharmacy.modules.partners.ports.inbound.SupplierView;
import tz.co.pharmacy.modules.purchasing.ports.outbound.PurchasingDirectory;
import tz.co.pharmacy.modules.tenancy.ports.inbound.BranchService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.BranchView;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * Binds the names purchasing keeps on its documents to partners, catalogue and tenancy; reads only.
 */
@Component
@RequiredArgsConstructor
class PurchasingDirectoryAdapter implements PurchasingDirectory {

  private final SupplierService supplierService;
  private final CatalogueService catalogueService;
  private final BranchService branchService;
  private final tz.co.pharmacy.modules.tenancy.ports.inbound.SettingsService settings;

  @Override
  public boolean vatRegistered() {
    return settings.current().vatRegistered();
  }

  @Override
  public String supplierName(UUID supplierUid) {
    return supplierService
        .find(supplierUid)
        .map(SupplierView::name)
        .orElseThrow(
            () -> Refusal.invalid("supplier_not_found", "No such supplier.", "supplierUid"));
  }

  @Override
  public String productName(UUID productUid) {
    return catalogueService
        .product(productUid)
        .map(ProductView::name)
        .orElseThrow(() -> Refusal.invalid("product_not_found", "No such product.", "productUid"));
  }

  @Override
  public String trackingMode(UUID productUid) {
    return catalogueService
        .product(productUid)
        .map(product -> product.trackingMode().name())
        .orElseThrow(() -> Refusal.invalid("product_not_found", "No such product.", "productUid"));
  }

  @Override
  public String branchCode(UUID branchUid) {
    return branchService
        .find(branchUid)
        .map(BranchView::code)
        .orElseThrow(() -> Refusal.forbidden("branch_not_allowed", "No such branch."));
  }
}
