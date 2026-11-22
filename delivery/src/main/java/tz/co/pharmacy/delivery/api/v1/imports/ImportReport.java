package tz.co.pharmacy.delivery.api.v1.imports;

import java.util.List;

/**
 * The answer to a check or an import. {@code rows} counts the rows read, {@code creating} and
 * {@code updating} what they would add and change. {@code saved} is true only when an import wrote
 * every row; with any problem nothing is written. {@code problems} lists at most {@link #SHOWN},
 * {@code problemCount} counts them all.
 */
public record ImportReport(
    String kind,
    int rows,
    int creating,
    int updating,
    boolean saved,
    int problemCount,
    List<ImportProblem> problems) {

  static final int SHOWN = 500;

  static ImportReport of(
      String kind, int rows, int creating, int updating, boolean saved, List<ImportProblem> all) {
    return new ImportReport(
        kind,
        rows,
        creating,
        updating,
        saved,
        all.size(),
        all.size() > SHOWN ? List.copyOf(all.subList(0, SHOWN)) : List.copyOf(all));
  }
}
