package tz.co.pharmacy.platform.persistence;

import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import java.util.Arrays;
import java.util.Collection;
import org.springframework.data.jpa.domain.Specification;

/** Small building blocks for list filters; each returns "no restriction" for a null value. */
public final class Specs {

  private Specs() {}

  public static <E> Specification<E> all() {
    return (root, query, cb) -> cb.conjunction();
  }

  /**
   * Any of the attributes contains {@code text}, in any case. Paths may be dotted: "category.name".
   */
  public static <E> Specification<E> containsAny(String text, String... attributes) {
    if (text == null || text.isBlank()) {
      return all();
    }
    String pattern = "%" + text.trim().toLowerCase().replace("%", "\\%").replace("_", "\\_") + "%";
    return (root, query, cb) ->
        cb.or(
            Arrays.stream(attributes)
                .map(
                    attribute -> {
                      Expression<String> path = path(root, attribute);
                      return cb.like(cb.lower(path), pattern, '\\');
                    })
                .toArray(Predicate[]::new));
  }

  public static <E> Specification<E> equal(String attribute, Object value) {
    if (value == null) {
      return all();
    }
    return (root, query, cb) -> cb.equal(path(root, attribute), value);
  }

  public static <E> Specification<E> in(String attribute, Collection<?> values) {
    if (values == null) {
      return all();
    }
    if (values.isEmpty()) {
      return (root, query, cb) -> cb.disjunction();
    }
    return (root, query, cb) -> path(root, attribute).in(values);
  }

  public static <E, Y extends Comparable<? super Y>> Specification<E> atLeast(
      String attribute, Y value) {
    if (value == null) {
      return all();
    }
    return (root, query, cb) -> cb.greaterThanOrEqualTo(path(root, attribute), value);
  }

  public static <E, Y extends Comparable<? super Y>> Specification<E> before(
      String attribute, Y value) {
    if (value == null) {
      return all();
    }
    return (root, query, cb) -> cb.lessThan(path(root, attribute), value);
  }

  @SuppressWarnings("unchecked")
  private static <T> Expression<T> path(jakarta.persistence.criteria.Path<?> root, String dotted) {
    jakarta.persistence.criteria.Path<?> path = root;
    for (String part : dotted.split("\\.")) {
      path = path.get(part);
    }
    return (Expression<T>) path;
  }
}
