package tz.co.pharmacy.modules.partners.ports.inbound;

import java.util.Optional;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;

/** The current tenant's customers. */
public interface CustomerService {

  /** {@code q} matches name, phone, email or code; newest first. */
  Page<CustomerView> search(String q, CustomerType type, PageQuery query);

  Optional<CustomerView> find(UUID uid);

  /** Gives the customer the next code, such as CUS-000001. */
  CustomerView create(CustomerCommand c);

  CustomerView change(UUID uid, CustomerCommand c);

  long count();
}
