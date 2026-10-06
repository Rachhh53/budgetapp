package app.budget.engine.schedule;

import java.time.LocalDate;

/** A named day on which banks are closed. */
public record Holiday(LocalDate date, String name) {}
