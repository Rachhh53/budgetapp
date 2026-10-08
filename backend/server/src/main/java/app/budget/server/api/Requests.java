package app.budget.server.api;

import io.javalin.http.Context;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;

/** Small questions asked about an incoming request in more than one place. */
public final class Requests {

    /** As much of an unwanted request body as is worth reading: Javalin's own default limit for a body. */
    private static final long DISCARD_LIMIT = 1_000_000;

    private Requests() {}

    /**
     * Reads and throws away whatever the client is still sending.
     *
     * <p>Why: a request can be refused before its body is looked at (locked, wrong host, unknown
     * id). If the answer goes out while the body is unread, the server closes the connection
     * with data pending, the operating system resets it, and the client can lose the answer —
     * it sees a dropped connection instead of the 401 that tells it to unlock and try again.
     * Anything over the limit is left alone; such a connection is simply closed.
     */
    public static void discardUnreadBody(Context ctx) {
        byte[] buffer = new byte[8192];
        long remaining = DISCARD_LIMIT;
        try {
            // Not closed here: the stream belongs to the server.
            InputStream body = ctx.req().getInputStream();
            int read;
            while (remaining > 0 && (read = body.read(buffer, 0, (int) Math.min(buffer.length, remaining))) != -1) {
                remaining -= read;
            }
        } catch (IOException | IllegalStateException e) {
            // The client has gone, or the body was already read another way: nothing left to do.
        }
    }

    /** True when the request was made from this PC itself (the desktop window), not the network. */
    public static boolean isFromThisPc(Context ctx) {
        try {
            // The servlet remote address is always a numeric literal, so this never does a DNS lookup.
            return InetAddress.getByName(ctx.req().getRemoteAddr()).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
