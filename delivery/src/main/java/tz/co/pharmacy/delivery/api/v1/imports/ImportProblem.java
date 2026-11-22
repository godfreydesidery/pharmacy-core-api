package tz.co.pharmacy.delivery.api.v1.imports;

/**
 * What is wrong in an upload: the row (as numbered in Excel, the headings being row 1; null for the
 * file as a whole), the column's heading (null for the whole row), a stable reason code and a
 * sentence for the person.
 */
public record ImportProblem(Integer row, String column, String reason, String message) {}
