package app.budget.server.reports;

import app.budget.engine.Money;
import app.budget.server.api.Inputs;
import app.budget.server.reports.ReportRunner.Named;
import app.budget.server.reports.ReportRunner.Report;
import app.budget.server.reports.ReportRunner.Row;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * A report's lines as a CSV file a spreadsheet opens: one row per thing spent, amounts as
 * plain numbers with two decimals.
 */
final class CsvExport {

    /** Tells Excel the file is UTF-8, so names in any language come through. */
    private static final String BYTE_ORDER_MARK = "﻿";
    private static final String LINE_END = "\r\n";

    private static final List<String> HEADER = List.of(
            "Date", "Description", "Paid to", "Kind", "Category", "Tags", "Paid from", "Amount", "Estimate", "Not paid yet",
            "Reimbursement", "Reimbursed", "Still owed", "Files");

    private CsvExport() {}

    /** @param names the names for each of the report's rows, in the same order */
    static byte[] of(Report report, List<Named> names) {
        StringBuilder csv = new StringBuilder(BYTE_ORDER_MARK);
        line(csv, HEADER);
        for (int i = 0; i < report.rows().size(); i++) {
            Row row = report.rows().get(i);
            Named named = names.get(i);
            line(csv, List.of(
                    row.date().toString(),
                    text(row.description()),
                    text(row.payee()),
                    kind(row.kind()),
                    text(named.category()),
                    text(named.tags()),
                    text(named.account()),
                    dollars(row.amountCents()),
                    row.estimate() ? "yes" : "",
                    row.unpaid() ? "yes" : "",
                    row.reimbursement() == null ? "" : words(Inputs.code(row.reimbursement())),
                    row.reimbursement() == null ? "" : dollars(row.reimbursedCents()),
                    row.reimbursement() == null ? "" : dollars(row.owedCents()),
                    String.valueOf(row.fileCount())));
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void line(StringBuilder csv, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                csv.append(',');
            }
            csv.append(quoted(cells.get(i)));
        }
        csv.append(LINE_END);
    }

    /** RFC 4180: a cell with a comma, a quote or a line break is wrapped in quotes, and its quotes doubled. */
    private static String quoted(String cell) {
        if (cell.contains(",") || cell.contains("\"") || cell.contains("\n") || cell.contains("\r")) {
            return "\"" + cell.replace("\"", "\"\"") + "\"";
        }
        return cell;
    }

    /**
     * Something the user typed, made safe to open in a spreadsheet: text that starts like a
     * formula ({@code =}, {@code +}, {@code -}, {@code @}) gets a leading apostrophe so it is
     * shown, never run.
     */
    private static String text(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        char first = value.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r'
                ? "'" + value
                : value;
    }

    static String kind(String kind) {
        return switch (kind) {
            case "one_off" -> "One-off";
            case "bill" -> "Bill or expense";
            default -> "Budget";
        };
    }

    /** {@code to_submit} → "To submit". */
    static String words(String code) {
        String spaced = code.replace('_', ' ');
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private static String dollars(long cents) {
        return Money.ofCents(cents).toDecimal().toPlainString();
    }
}
