package tz.co.pharmacy.modules.reporting.ports.inbound;

import java.util.List;

public record SalesReport(List<SalesReportRow> rows, SalesReportRow totals) {}
