package app.budget.server.auth;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Unlocked sessions, held in memory only: closing the app locks it, and nothing about a
 * session is ever written to disk. A session that sits idle past the auto-lock time expires.
 */
public final class SessionStore {

    private static final int TOKEN_BYTES = 32;

    private final Map<String, Instant> lastSeenByToken = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final Duration idleLimit;

    public SessionStore(Clock clock, Duration idleLimit) {
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
        // compute() makes "check idle time, then refresh or drop" one atomic step per token.
        return lastSeenByToken.compute(token, (key, lastSeen) ->
                lastSeen == null || lastSeen.plus(idleLimit).isBefore(now) ? null : now) != null;
    }

    public void revoke(String token) {
        if (token != null) {
            lastSeenByToken.remove(token);
        }
    }
}
