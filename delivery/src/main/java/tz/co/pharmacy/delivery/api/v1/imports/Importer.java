package tz.co.pharmacy.delivery.api.v1.imports;

import java.util.List;
import tz.co.pharmacy.modules.tenancy.ports.inbound.PlanLimit;

/**
 * One kind of import (products, customers…): the columns it reads, an example row for its template,
 * and how a batch reads, checks and saves rows. {@link ImportEngine} runs it: every row is read and
 * checked before anything is written, and an import writes all rows in one transaction or none.
 *
 * @param <P> what a row becomes once read: the change it will make
 */
interface Importer<P> {

  /** The route's name, such as {@code products}; also the template's file name. */
  String kind();

  List<ImportColumn> columns();

  /** One example row for the template, aligned with {@link #columns()}. */
  List<Object> example();

  /** The template's rows: the example, or what the record holds now (a price list's items). */
  default List<List<Object>> templateRows() {
    return List.of(example());
  }

  /** The plan cap the rows it creates count against, or null. */
  default PlanLimit limit() {
    return null;
  }

  /** A batch for one upload; it may keep lookups (categories, codes) for that upload only. */
  Batch<P> start();

  interface Batch<P> {

    /** The row as a change, or null when it has problems (recorded on the row). */
    P read(ImportRow row);

    /** Whether the change adds a record (rather than changing one). */
    boolean creates(P plan);

    /** Values that may appear only once in a file, such as a product's code. */
    default List<Key> keys(P plan) {
      return List.of();
    }

    /** Writes the change; a {@code Refusal} names what the rules refuse. */
    void save(P plan);

    /**
     * After every row is saved, in the same transaction: for records saved whole (a price list and
     * its items), which gather the rows in {@link #save} and write them here.
     */
    default void finish() {}
  }

  /** A value that must be unique in the file, and the column it is in. */
  record Key(String column, String value) {}
}
