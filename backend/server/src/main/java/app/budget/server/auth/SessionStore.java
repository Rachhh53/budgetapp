package app.budget.server.auth;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Unlocked sessions, held in memory only: closing the app locks it, and nothing about a
 * session is ever written to disk. A session that sits idle past the auto-lock time expires.
 *
 * <p>The auto-lock time is a setting, so it is asked for each time rather than fixed at
 * startup: a change in Settings applies to the sessions already open.
 */
public final class SessionStore {

    private static final int TOKEN_BYTES = 32;

    private final Map<String, Instant> lastSeenByToken = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final Supplier<Duration> idleLimit;

    public SessionStore(Clock clock, Supplier<Duration> idleLimit) {
        this.clock = clock;
        this.idleLimit = idleLimit;
    }

    /** Starts a session and returns its opaque token (256 random bits — it carries no meaning). */
    public String issue() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        lastSeenByToken.put(token, clock.instant());
        return token;
    }

    /** True if the token names a live session; also counts as activity, pushing auto-lock back. */
    public boolean touch(String token) {
        if (token == null) {
            return false;
        }
        Instant now = clock.instant();
        Duration limit = idleLimit.get();
        // compute() makes "check idle time, then refresh or drop" one atomic step per token.
        return lastSeenByToken.compute(token, (key, lastSeen) ->
                lastSeen == null || lastSeen.plus(limit).isBefore(now) ? null : now) != null;
    }

    /** Ends every session at once (used when all data is erased, or a forgotten PIN is reset). */
    public void revokeAll() {
        lastSeenByToken.clear();
    }

    /** Ends every session but the one that asked (used when the PIN is set or changed). */
    public void revokeAllExcept(String token) {
        lastSeenByToken.keySet().removeIf(other -> !other.equals(token));
    }

    public void revoke(String token) {
        if (token != null) {
            lastSeenByToken.remove(token);
        }
    }
}
