package app.budget.server.schedule;

import app.budget.server.api.ApiException;
import app.budget.server.api.ApiResponse;
import app.budget.server.api.Inputs;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import java.time.Clock;
import java.time.LocalDate;

/**
 * {@code /api/holidays}: the days paydays move around. US bank holidays are filled in
 * locally; every one can be switched off, changed or removed, and the user can add their own.
 */
public final class HolidaysRoutes {

    private static final int NAME_MAX = 80;
    private static final String DATE_TAKEN = "There's already a holiday on that date.";

    private final HolidaysRepository holidays;
    private final Clock clock;

    public HolidaysRoutes(HolidaysRepository holidays, Clock clock) {
        this.holidays = holidays;
        this.clock = clock;
    }

    public void register(JavalinDefaultRoutingApi routes) {
        routes.get("/api/holidays", this::list);
        routes.post("/api/holidays", this::create);
        routes.put("/api/holidays/{id}", this::update);
        routes.delete("/api/holidays/{id}", this::delete);
    }

    /** Request body. {@code enabled} is only read on an update. */
    public record HolidayInput(LocalDate date, String name, Boolean enabled) {}

    public record Saved(boolean saved) {}

    /** Holidays between {@code from} and {@code to}; by default from today to the end of next year. */
    private void list(Context ctx) {
        LocalDate today = LocalDate.now(clock);
        LocalDate from = Inputs.dateParam(ctx, "from", today);
        LocalDate to = Inputs.dateParam(ctx, "to", LocalDate.of(today.getYear() + 1, 12, 31));
        Inputs.requireSensibleRange(from, to);
        ApiResponse.ok(ctx, holidays.between(from, to));
    }

    private void create(Context ctx) {
        HolidayInput input = Inputs.body(ctx, HolidayInput.class);
        LocalDate date = date(input);
        String name = Inputs.text(input.name(), "a name for the holiday", NAME_MAX);
        if (holidays.dateTaken(date, null)) {
            throw ApiException.conflict(DATE_TAKEN);
        }
        holidays.create(date, name);
        ApiResponse.send(ctx, 201, new Saved(true));
    }

    private void update(Context ctx) {
        long id = Inputs.id(ctx, "id");
        HolidayInput input = Inputs.body(ctx, HolidayInput.class);
        LocalDate date = date(input);
        String name = Inputs.text(input.name(), "a name for the holiday", NAME_MAX);
        if (holidays.dateOf(id).isEmpty()) {
            throw ApiException.notFound();
        }
        if (holidays.dateTaken(date, id)) {
            throw ApiException.conflict(DATE_TAKEN);
        }
        // Leaving `enabled` out keeps the holiday switched on.
        holidays.update(id, date, name, !Boolean.FALSE.equals(input.enabled()));
        ApiResponse.ok(ctx, new Saved(true));
    }

    private void delete(Context ctx) {
        if (!holidays.delete(Inputs.id(ctx, "id"))) {
            throw ApiException.notFound();
        }
        ApiResponse.ok(ctx, new Saved(true));
    }

    private static LocalDate date(HolidayInput input) {
        if (input.date() == null) {
            throw ApiException.badRequest("Please choose a date for the holiday.");
        }
        if (!Inputs.inSupportedYears(input.date())) {
            throw ApiException.badRequest("Please choose a date between 2000 and 2100.");
        }
        return input.date();
    }
}
