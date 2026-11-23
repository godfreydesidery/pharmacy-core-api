package tz.co.pharmacy.platform.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.hibernate.query.criteria.JpaCriteriaQuery;
import org.hibernate.query.criteria.JpaRoot;
import org.hibernate.query.criteria.JpaSubQuery;
import org.springframework.core.ResolvableType;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;

/**
 * Keyset paging, newest first: a page is the {@code limit} rows with the highest uids below the
 * cursor. UUIDv7 uids sort by creation time, so this needs no count and no offset and stays fast on
 * tables of any size.
 *
 * <p>A numbered page (a table's page numbers) is read at its offset and carries the list's total,
 * counted at most to {@link Page#TOTAL_CAP} + 1 rows ({@code select count(*) from (select uid ...
 * fetch first 10001 rows only)}): a table of a million rows costs a page number no more than one of
 * ten thousand (adversarial review PERF-5). The last page, and a first page that is the whole list,
 * need no count at all.
 */
public final class Keyset {

  private Keyset() {}

  public static <E extends RecordedEntity, R> Page<R> page(
      JpaSpecificationExecutor<E> repository,
      Specification<E> filter,
      PageQuery query,
      Function<E, R> mapping) {
    if (query.numbered()) {
      return numbered(repository, filter, query).map(mapping);
    }
    UUID after = query.afterUid();
    Specification<E> spec = after == null ? filter : filter.and(Specs.before("uid", after));
    List<E> rows =
        repository.findBy(
            spec,
            q -> q.sortBy(Sort.by(Sort.Direction.DESC, "uid")).limit(query.limit() + 1).all());
    return Page.keyset(rows, query, RecordedEntity::getUid).map(mapping);
  }

  /** One numbered page, newest uid first, with its total counted up to the cap. */
  private static <E extends RecordedEntity> Page<E> numbered(
      JpaSpecificationExecutor<E> repository, Specification<E> filter, PageQuery query) {
    EntityManager entityManager = transactionEntityManager();
    Class<E> type = entityType(repository);
    if (entityManager == null || type == null) {
      // Outside a transaction (not how the services call it): Spring Data's page, counted whole.
      org.springframework.data.domain.Page<E> page =
          repository.findAll(
              filter,
              org.springframework.data.domain.PageRequest.of(
                  query.page() - 1, query.limit(), Sort.by(Sort.Direction.DESC, "uid")));
      return Page.numbered(page.getContent(), page.getTotalElements());
    }
    CriteriaBuilder builder = entityManager.getCriteriaBuilder();
    CriteriaQuery<E> select = builder.createQuery(type);
    Root<E> root = select.from(type);
    Predicate where = filter.toPredicate(root, select, builder);
    if (where != null) {
      select.where(where);
    }
    select.select(root).orderBy(builder.desc(root.get("uid")));
    List<E> rows =
        entityManager
            .createQuery(select)
            .setFirstResult(query.offset())
            .setMaxResults(query.limit())
            .getResultList();
    // As Spring Data does: a first page shorter than the limit is the whole list, and a short page
    // further on is the last one, so its total is known without counting.
    if (query.offset() == 0 && rows.size() < query.limit()) {
      return Page.numbered(rows, rows.size());
    }
    if (!rows.isEmpty() && rows.size() < query.limit()) {
      return Page.numberedCapped(rows, (long) query.offset() + rows.size());
    }
    return Page.numberedCapped(rows, cappedCount(entityManager, type, filter));
  }

  /**
   * How many rows {@code filter} matches, counted at most to {@link Page#TOTAL_CAP} + 1: {@code
   * select count(*) from (select e.uid from E e where ... fetch first 10001 rows only)}, a derived
   * table of Hibernate's criteria API.
   */
  static <E> long cappedCount(EntityManager entityManager, Class<E> type, Specification<E> filter) {
    HibernateCriteriaBuilder builder =
        (HibernateCriteriaBuilder) entityManager.getCriteriaBuilder();
    JpaCriteriaQuery<Long> count = builder.createQuery(Long.class);
    JpaSubQuery<UUID> rows = count.subquery(UUID.class);
    JpaRoot<E> root = rows.from(type);
    Predicate where = filter.toPredicate(root, count, builder);
    if (where != null) {
      rows.where(where);
    }
    jakarta.persistence.criteria.Path<UUID> uid = root.get("uid");
    // A derived table's columns need names.
    uid.alias("uid");
    rows.select(uid);
    if (count.isDistinct()) {
      // A filter that asked for distinct rows (a join to many) asked it of the count query.
      count.distinct(false);
      rows.distinct(true);
    }
    rows.fetch(Page.TOTAL_CAP + 1);
    count.from(rows);
    count.select(builder.count());
    return entityManager.createQuery(count).getSingleResult();
  }

  /** The entity manager of the JPA transaction under way on this thread, or null. */
  private static EntityManager transactionEntityManager() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      return null;
    }
    for (Object resource : TransactionSynchronizationManager.getResourceMap().values()) {
      if (resource instanceof EntityManagerHolder holder) {
        return holder.getEntityManager();
      }
    }
    return null;
  }

  /** The entity a repository serves, from its declared {@code JpaSpecificationExecutor<E>}. */
  @SuppressWarnings("unchecked")
  private static <E> Class<E> entityType(JpaSpecificationExecutor<E> repository) {
    return (Class<E>)
        ResolvableType.forClass(repository.getClass())
            .as(JpaSpecificationExecutor.class)
            .getGeneric(0)
            .resolve();
  }
}
