package app.budget.server.auth;

import app.budget.server.auth.AuthRepository.AuthState;
import com.password4j.Argon2Function;
import com.password4j.Password;
import com.password4j.types.Argon2;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The app lock (spec §7): an optional PIN, the recovery code that can reset it, and the
 * growing wait after wrong tries.
 *
 * <p>The PIN is a lock on the app, not encryption of the data. Neither secret is ever stored,
 * logged or sent back: only Argon2id hashes are kept, and the recovery code is handed out
 * once, at the moment it is made.
 *
 * <p>Every method is synchronized so that tries are counted one at a time: two guesses sent
 * together cannot both slip in ahead of the wait.
 */
public final class PinService {

    /** What became of a try at the PIN or the recovery code. */
    public enum Outcome {
        OK,
        /** It did not match; the try was counted. */
        WRONG,
        /** Too many wrong tries: nothing is looked at until the wait is over. */
        WAIT
    }

    /**
     * @param pinSet            true when a PIN is in front of the app
     * @param retryAfterSeconds how long until the next try is looked at; 0 when there is no wait
     * @param offerPending      true while the first-launch offer to set a PIN is still to be answered
     */
    public record Status(boolean pinSet, long retryAfterSeconds, boolean offerPending) {}

    public static final String PIN_HINT = "A PIN is 4 to 8 digits.";

    private static final Pattern PIN = Pattern.compile("\\d{4,8}");

    /**
     * Argon2id with 19 MiB of memory, 2 passes and 1 lane: OWASP's baseline, and about a
     * twentieth of a second on an ordinary PC. A short PIN has few possibilities, so the wait
     * after wrong tries is what really protects it; the hash keeps it out of plain sight in the file.
     */
    private static final Argon2Function ARGON2 = Argon2Function.getInstance(19_456, 2, 1, 32, Argon2.ID);
    private static final int SALT_BYTES = 16;

    /** Letters and digits that cannot be mistaken for one another when copied by hand (no 0/O, 1/I). */
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_GROUPS = 4;
    private static final int CODE_GROUP_LENGTH = 4;
    private static final Pattern CODE = Pattern.compile("[" + CODE_ALPHABET + "]{" + CODE_GROUPS * CODE_GROUP_LENGTH + "}");

    /** Wrong tries that cost nothing; from the next one on there is a wait. */
    static final int FREE_ATTEMPTS = 2;
    /** The wait after the 3rd, 4th, 5th and 6th wrong try in a row; every one after that waits the last. */
    private static final Duration[] WAITS = {
        Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofHours(1)
    };

    private final AuthRepository auth;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public PinService(AuthRepository auth, Clock clock) {
        this.auth = auth;
        this.clock = clock;
    }

    public synchronized Status status() {
        AuthState state = auth.read();
        return new Status(state.pinSet(), secondsLeft(state), !state.pinSet() && !state.offerDismissed());
    }

    public static boolean isValidPin(String pin) {
        return pin != null && PIN.matcher(pin).matches();
    }

    /** Tries a PIN. A missing one is not a guess and is not counted. */
    public synchronized Outcome checkPin(String pin) {
        if (pin == null || pin.isEmpty()) {
            return secondsLeft(auth.read()) > 0 ? Outcome.WAIT : Outcome.WRONG;
        }
        // Anything that is not 4 to 8 digits cannot be the PIN, so it is never hashed.
        return attempt(state -> isValidPin(pin) && matches(pin, state.pinHash()));
    }

    /** Tries a recovery code, typed with or without its dashes, in either case. */
    public synchronized Outcome checkRecoveryCode(String typed) {
        String code = typed == null ? "" : typed.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        return attempt(state -> CODE.matcher(code).matches() && matches(code, state.recoveryCodeHash()));
    }

    /**
     * Sets the first PIN and makes its recovery code.
     *
     * @return the recovery code, shown once and never again
     */
    public synchronized String setPin(String pin) {
        String code = newCode();
        auth.write(auth.read().withSecrets(hash(pin), hash(compact(code))));
        return code;
    }

    /** Replaces the PIN and keeps the recovery code that is already written down. */
    public synchronized void changePin(String pin) {
        AuthState state = auth.read();
        auth.write(state.withSecrets(hash(pin), state.recoveryCodeHash()));
    }

    /**
     * Replaces the recovery code; the old one stops working.
     *
     * @return the new code, shown once and never again
     */
    public synchronized String newRecoveryCode() {
        String code = newCode();
        AuthState state = auth.read();
        auth.write(state.withSecrets(state.pinHash(), hash(compact(code))));
        return code;
    }

    public synchronized void removePin() {
        auth.clearPin();
    }

    public synchronized void dismissOffer() {
        auth.write(auth.read().withOfferDismissed());
    }

    /** "30 seconds", "1 minute", "15 minutes": the wait in words, rounded up. */
    public static String inWords(long seconds) {
        if (seconds < 60) {
            return seconds + (seconds == 1 ? " second" : " seconds");
        }
        long minutes = (seconds + 59) / 60;
        return minutes + (minutes == 1 ? " minute" : " minutes");
    }

    private Outcome attempt(Predicate<AuthState> matches) {
        AuthState state = auth.read();
        if (!state.pinSet()) {
            return Outcome.WRONG;
        }
        if (secondsLeft(state) > 0) {
            return Outcome.WAIT;
        }
        if (matches.test(state)) {
            if (state.failedAttempts() > 0 || state.lockedUntil() != null) {
                auth.write(state.withAttempts(0, null));
            }
            return Outcome.OK;
        }
        int attempts = state.failedAttempts() + 1;
        Duration wait = waitAfter(attempts);
        auth.write(state.withAttempts(attempts, wait.isZero() ? null : clock.instant().plus(wait)));
        return Outcome.WRONG;
    }

    static Duration waitAfter(int attempts) {
        if (attempts <= FREE_ATTEMPTS) {
            return Duration.ZERO;
        }
        return WAITS[Math.min(attempts - FREE_ATTEMPTS, WAITS.length) - 1];
    }

    private long secondsLeft(AuthState state) {
        Instant now = clock.instant();
        if (state.lockedUntil() == null || !now.isBefore(state.lockedUntil())) {
            return 0;
        }
        // Rounded up, so "0 seconds" is never shown while there is still a wait.
        return Duration.between(now, state.lockedUntil()).plusMillis(999).toSeconds();
    }

    private static String hash(String secret) {
        return Password.hash(secret).addRandomSalt(SALT_BYTES).with(ARGON2).getResult();
    }

    private static boolean matches(String secret, String hash) {
        // The settings a hash was made with are read back from the hash itself, so they can be
        // strengthened later without locking anyone out.
        return hash != null && Password.check(secret, hash).with(Argon2Function.getInstanceFromHash(hash));
    }

    /** A new code such as {@code K7QM-2XHP-9FTB-WRNC}: 80 random bits. */
    private String newCode() {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < CODE_GROUPS * CODE_GROUP_LENGTH; i++) {
            if (i > 0 && i % CODE_GROUP_LENGTH == 0) {
                code.append('-');
            }
            code.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        return code.toString();
    }

    private static String compact(String code) {
        return code.replace("-", "");
    }
}
