package tz.co.pharmacy.modules.partners.ports.inbound;

import java.util.Optional;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;

/** The current tenant's suppliers. */
public interface SupplierService {

  /** {@code q} matches name, phone, email or code; newest first. */
  Page<SupplierView> search(String q, PageQuery query);

  Optional<SupplierView> find(UUID uid);

  /** Gives the supplier the next code, such as SUP-000001. */
  SupplierView create(SupplierCommand c);

  SupplierView change(UUID uid, SupplierCommand c);
}
