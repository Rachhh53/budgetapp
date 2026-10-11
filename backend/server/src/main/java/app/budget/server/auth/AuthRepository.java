package app.budget.server.auth;

import java.time.Instant;
import org.jdbi.v3.core.Jdbi;

/** Reads and writes the single {@code auth} row: the PIN, its recovery code and the wait after wrong tries. */
public final class AuthRepository {

    /**
     * @param pinHash          Argon2id hash of the PIN; {@code null} when no PIN is set
     * @param recoveryCodeHash Argon2id hash of the recovery code; set exactly when the PIN is
     * @param failedAttempts   wrong tries in a row
     * @param lockedUntil      no try is looked at before this; {@code null} when there is no wait
     * @param offerDismissed   true once the first-launch offer to set a PIN has been answered
     */
    public record AuthState(
            String pinHash, String recoveryCodeHash, int failedAttempts, Instant lockedUntil, boolean offerDismissed) {

        static final AuthState EMPTY = new AuthState(null, null, 0, null, false);

        public boolean pinSet() {
            return pinHash != null;
        }

        AuthState withSecrets(String newPinHash, String newRecoveryCodeHash) {
            return new AuthState(newPinHash, newRecoveryCodeHash, 0, null, true);
        }

        AuthState withAttempts(int attempts, Instant until) {
            return new AuthState(pinHash, recoveryCodeHash, attempts, until, offerDismissed);
        }

        AuthState withOfferDismissed() {
            return new AuthState(pinHash, recoveryCodeHash, failedAttempts, lockedUntil, true);
        }
    }

    private final Jdbi jdbi;

    public AuthRepository(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public AuthState read() {
        return jdbi.withHandle(handle -> handle
                .createQuery("SELECT pin_hash, recovery_code_hash, failed_attempts, locked_until, offer_dismissed FROM auth WHERE id = 1")
                .map((rs, ctx) -> new AuthState(
                        rs.getString("pin_hash"),
                        rs.getString("recovery_code_hash"),
                        rs.getInt("failed_attempts"),
                        rs.getString("locked_until") == null ? null : Instant.parse(rs.getString("locked_until")),
                        rs.getBoolean("offer_dismissed")))
                .findOne()
                .orElse(AuthState.EMPTY));
    }

    public void write(AuthState state) {
        jdbi.useHandle(handle -> handle
                .createUpdate("""
                        INSERT INTO auth (id, pin_hash, recovery_code_hash, failed_attempts, locked_until, offer_dismissed)
                        VALUES (1, :pinHash, :recoveryCodeHash, :failedAttempts, :lockedUntil, :offerDismissed)
                        ON CONFLICT (id) DO UPDATE SET
                            pin_hash = excluded.pin_hash,
                            recovery_code_hash = excluded.recovery_code_hash,
                            failed_attempts = excluded.failed_attempts,
                            locked_until = excluded.locked_until,
                            offer_dismissed = excluded.offer_dismissed
                        """)
                .bind("pinHash", state.pinHash())
                .bind("recoveryCodeHash", state.recoveryCodeHash())
                .bind("failedAttempts", state.failedAttempts())
                .bind("lockedUntil", state.lockedUntil() == null ? null : state.lockedUntil().toString())
                .bind("offerDismissed", state.offerDismissed() ? 1 : 0)
                .execute());
    }

    /**
     * Removes the PIN and its recovery code, and any wait. The offer to set one stays answered:
     * someone who removed their PIN has already decided.
     */
    public void clearPin() {
        AuthState state = read();
        write(new AuthState(null, null, 0, null, state.offerDismissed()));
    }
}
