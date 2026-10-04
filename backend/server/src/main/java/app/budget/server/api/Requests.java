package app.budget.server.api;

import io.javalin.http.Context;
import java.net.InetAddress;
import java.net.UnknownHostException;

/** Small questions asked about an incoming request in more than one place. */
public final class Requests {

    private Requests() {}

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
