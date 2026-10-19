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
 *
 * <p>Each session remembers whether it was started on this PC or from another device (phone
 * access, spec §10 Phase 12), and is only accepted from the same kind of place: a token copied
 * off this PC can't be used over the network, nor a phone's from this PC. Turning phone access
 * off ends every session from another device at once.
 */
public final class SessionStore {

    private static final int TOKEN_BYTES = 32;

    /** @param fromThisPc true when the session was unlocked on this PC itself */
    private record Session(Instant lastSeen, boolean fromThisPc) {}

    private final Map<String, Session> sessionsByToken = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final Supplier<Duration> idleLimit;

    public SessionStore(Clock clock, Supplier<Duration> idleLimit) {
        this.clock = clock;
        this.idleLimit = idleLimit;
    }

    /**
     * Starts a session and returns its opaque token (256 random bits — it carries no meaning).
     *
     * @param fromThisPc true when it is being unlocked on this PC, false from another device
     */
    public String issue(boolean fromThisPc) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessionsByToken.put(token, new Session(clock.instant(), fromThisPc));
        return token;
    }

    /**
     * True if the token names a live session started in the same kind of place this request
     * comes from; also counts as activity, pushing auto-lock back.
     */
    public boolean touch(String token, boolean fromThisPc) {
        if (token == null) {
            return false;
        }
        Instant now = clock.instant();
        Duration limit = idleLimit.get();
        boolean[] live = new boolean[1];
        // compute() makes "check idle time, then refresh or drop" one atomic step per token.
        sessionsByToken.compute(token, (key, session) -> {
            if (session == null || session.lastSeen().plus(limit).isBefore(now)) {
                return null;
            }
            // From the wrong kind of place the session is refused, but left as it is for its owner.
            live[0] = session.fromThisPc() == fromThisPc;
            return live[0] ? new Session(now, fromThisPc) : session;
        });
        return live[0];
    }

    /** Ends every session at once (used when all data is erased, or a forgotten PIN is reset). */
    public void revokeAll() {
        sessionsByToken.clear();
    }

    /** Ends every session but the one that asked (used when the PIN is set or changed). */
    public void revokeAllExcept(String token) {
        sessionsByToken.keySet().removeIf(other -> !other.equals(token));
    }

    /** Ends every session from another device (used when phone access is turned off). */
    public void revokeOtherDevices() {
        sessionsByToken.values().removeIf(session -> !session.fromThisPc());
    }

    public void revoke(String token) {
        if (token != null) {
            sessionsByToken.remove(token);
        }
    }
}
