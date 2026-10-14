package app.budget.server.planner;

import java.util.Set;
import org.jdbi.v3.core.Jdbi;

/** Reads and writes {@code planner_hidden_accounts}: the accounts left out of the comparison. */
public final class PlannerRepository {

    private final Jdbi jdbi;

    public PlannerRepository(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public Set<Long> hidden() {
        return jdbi.withHandle(handle -> handle
                .createQuery("SELECT account_id FROM planner_hidden_accounts")
                .mapTo(Long.class)
                .set());
    }

    /** Leaves an account out. Hiding one that is already hidden changes nothing. */
    public void hide(long accountId) {
        jdbi.useHandle(handle -> handle
                .createUpdate("INSERT INTO planner_hidden_accounts (account_id) VALUES (:id) ON CONFLICT (account_id) DO NOTHING")
                .bind("id", accountId)
                .execute());
    }

    public void show(long accountId) {
        jdbi.useHandle(handle -> handle
                .createUpdate("DELETE FROM planner_hidden_accounts WHERE account_id = :id")
                .bind("id", accountId)
                .execute());
    }
}
