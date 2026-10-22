package app.budget.server.api;

import java.time.LocalDate;

/**
 * The day an account, recurring item, bucket or asset was archived as of (V39). Before that day it counts exactly as
 * it did, in every pay period, chart and total; from that day on it is put away (Rachel, 2026-10-22: "the previous
 * activity needs to remain"; "this should be true for all things that can be archived").
 */
public final class Archiving {

    private Archiving() {}

    /**
     * The day to store for something being saved.
     *
     * @param archived what the request asks for; {@code null} or false keeps it out of the archive
     * @param given    the day the request names, if any
     * @param before   the day it was archived as of until now, kept when no day is given; {@code null} if it wasn't
     * @param today    the default for something archived just now
     * @param earliest a day it cannot be archived before (a bucket's first day), or {@code null}
     * @return {@code null} when it is not archived
     */
    public static LocalDate archivedOn(Boolean archived, LocalDate given, LocalDate before, LocalDate today, LocalDate earliest) {
        if (!Boolean.TRUE.equals(archived)) {
            return null;
        }
        if (given == null) {
            return before != null ? before : today;
        }
        // Archived ahead of time, it would vanish from today's lists while still counting: so not after today.
        if (!Inputs.inSupportedYears(given) || given.isAfter(today)) {
            throw ApiException.badRequest("Please choose today or an earlier day to archive it as of.");
        }
        if (earliest != null && given.isBefore(earliest)) {
            throw ApiException.badRequest("Please choose a day on or after the day it counts from to archive it as of.");
        }
        return given;
    }

    /** True when something archived as of {@code archivedOn} is put away on {@code day}. */
    public static boolean awayOn(LocalDate archivedOn, LocalDate day) {
        return archivedOn != null && !day.isBefore(archivedOn);
    }
}
