package tz.co.pharmacy.platform.kernel;

/**
 * A view of a row that people edit, carrying the row's optimistic-locking version ({@code
 * MutableEntity#version}). The edge sends it as the entity tag of the resource ({@code ETag: "7"})
 * and compares a change's {@code If-Match} with it (RFC 9110 §8.8.3, §13.1.1): 412
 * precondition_failed when someone else changed the row since it was read.
 */
public interface Versioned {

  long version();
}
