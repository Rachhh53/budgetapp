package app.budget.server.uploads;

import app.budget.engine.statements.CsvStatement;
import app.budget.engine.statements.StatementText.DateOrder;
import app.budget.server.api.Inputs;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.core.Jdbi;

/**
 * Which column of an account's CSV downloads holds what (table {@code statement_csv_layouts}), asked once and found again
 * by the file's header row (decided with Rachel, 2026-10-25). A file with no header row is found by how many columns
 * it has instead, since its first row is a line that changes every time.
 */
public final class CsvLayouts {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Jdbi jdbi;

    public CsvLayouts(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    /** The layout as stored: plain fields, with how dates are written in its API spelling. */
    record Stored(
            boolean hasHeader,
            int dateColumn,
            Integer postedColumn,
            int descriptionColumn,
            Integer amountColumn,
            Integer debitColumn,
            Integer creditColumn,
            String dateOrder,
            boolean chargesNegative) {}

    /** The layout remembered for a file whose first row is {@code firstRow}. */
    public Optional<CsvStatement.Layout> find(long accountId, List<String> firstRow) {
        Optional<CsvStatement.Layout> byHeader = load(accountId, CsvStatement.signature(firstRow)).filter(CsvStatement.Layout::hasHeader);
        return byHeader.isPresent() ? byHeader : load(accountId, byColumns(firstRow)).filter(layout -> !layout.hasHeader());
    }

    /** Remembers the layout a file was read with, for the next download from the same bank. */
    public void remember(long accountId, List<String> firstRow, CsvStatement.Layout layout) {
        String signature = layout.hasHeader() ? CsvStatement.signature(firstRow) : byColumns(firstRow);
        String stored;
        try {
            stored = JSON.writeValueAsString(new Stored(layout.hasHeader(), layout.dateColumn(), layout.postedColumn(),
                    layout.descriptionColumn(), layout.amountColumn(), layout.debitColumn(), layout.creditColumn(),
                    Inputs.code(layout.dateOrder()), layout.chargesNegative()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A CSV layout could not be written down", e);
        }
        jdbi.useHandle(handle -> handle.createUpdate("""
                        INSERT INTO statement_csv_layouts (account_id, signature, layout) VALUES (:accountId, :signature, :layout)
                        ON CONFLICT (account_id, signature) DO UPDATE SET layout = excluded.layout
                        """)
                .bind("accountId", accountId)
                .bind("signature", signature)
                .bind("layout", stored)
                .execute());
    }

    private Optional<CsvStatement.Layout> load(long accountId, String signature) {
        return jdbi.withHandle(handle -> handle
                        .createQuery("SELECT layout FROM statement_csv_layouts WHERE account_id = :accountId AND signature = :signature")
                        .bind("accountId", accountId)
                        .bind("signature", signature)
                        .mapTo(String.class)
                        .findOne())
                .flatMap(CsvLayouts::parse);
    }

    private static Optional<CsvStatement.Layout> parse(String stored) {
        try {
            Stored layout = JSON.readValue(stored, Stored.class);
            return Optional.of(new CsvStatement.Layout(layout.hasHeader(), layout.dateColumn(), layout.postedColumn(),
                    layout.descriptionColumn(), layout.amountColumn(), layout.debitColumn(), layout.creditColumn(),
                    Inputs.choice(DateOrder.class, layout.dateOrder(), "how the dates are written"), layout.chargesNegative()));
        } catch (JsonProcessingException | RuntimeException e) {
            // A layout this version can't read is asked again, as if it had never been remembered.
            return Optional.empty();
        }
    }

    private static String byColumns(List<String> firstRow) {
        return "columns:" + firstRow.size();
    }
}
