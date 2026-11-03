package tz.co.pharmacy.platform.persistence;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;

/**
 * Keyset paging, newest first: a page is the {@code limit} rows with the highest uids below the
 * cursor. UUIDv7 uids sort by creation time, so this needs no count and no offset and stays fast on
 * tables of any size.
 */
public final class Keyset {

  private Keyset() {}

  public static <E extends RecordedEntity, R> Page<R> page(
      JpaSpecificationExecutor<E> repository,
      Specification<E> filter,
      PageQuery query,
      Function<E, R> mapping) {
    UUID after = query.afterUid();
    Specification<E> spec = after == null ? filter : filter.and(Specs.before("uid", after));
    List<E> rows =
        repository.findBy(
            spec,
            q -> q.sortBy(Sort.by(Sort.Direction.DESC, "uid")).limit(query.limit() + 1).all());
    return Page.keyset(rows, query, RecordedEntity::getUid).map(mapping);
  }
}
