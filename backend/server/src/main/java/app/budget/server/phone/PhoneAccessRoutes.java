package app.budget.server.phone;

import app.budget.server.api.ApiException;
import app.budget.server.api.ApiResponse;
import app.budget.server.api.Inputs;
import app.budget.server.api.Requests;
import app.budget.server.auth.PinService;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import java.util.List;

/**
 * {@code GET /api/phone-access} and {@code PUT /api/phone-access}: Settings → Phone access.
 *
 * <p>Turning it on or off, or choosing the network, is for this PC only: a phone can't open the
 * app to more of the network, or move it to another one. Nor does a phone get the list of this
 * PC's networks; it is told only whether phone access is on and the address it is using.
 */
public final class PhoneAccessRoutes {

    private static final String THIS_PC_ONLY = "For safety, phone access can only be changed on the PC Budget is installed on.";

    private final PhoneAccess phoneAccess;
    private final PinService pins;

    public PhoneAccessRoutes(PhoneAccess phoneAccess, PinService pins) {
        this.phoneAccess = phoneAccess;
        this.pins = pins;
    }

    public void register(JavalinDefaultRoutingApi routes) {
        routes.get("/api/phone-access", this::show);
        routes.put("/api/phone-access", this::change);
    }

    /**
     * @param status     whether it is on, where, and whether a phone can reach it right now
     * @param fromThisPc true when asked on this PC: only then are the networks listed and can it be changed
     * @param pinSet     true when a PIN is set, which phone access needs
     * @param networks   this PC's VPNs and other tunnels (never Wi-Fi or a cable); empty when asked from another device
     */
    public record View(PhoneAccess.Status status, boolean fromThisPc, boolean pinSet, List<Networks.Network> networks) {}

    /** Request body: the network address to listen on, or {@code null} (or empty) to turn phone access off. */
    public record Change(String address) {}

    private View view(Context ctx) {
        boolean fromThisPc = Requests.isFromThisPc(ctx);
        return new View(phoneAccess.status(), fromThisPc, pins.status().pinSet(),
                fromThisPc ? phoneAccess.networks() : List.of());
    }

    private void show(Context ctx) {
        // Asking is also a good moment to catch up with a network that has just come or gone.
        phoneAccess.keepUp();
        ApiResponse.ok(ctx, view(ctx));
    }

    private void change(Context ctx) {
        if (!Requests.isFromThisPc(ctx)) {
            throw ApiException.forbidden(THIS_PC_ONLY);
        }
        Change change = Inputs.body(ctx, Change.class);
        String address = change.address() == null ? "" : change.address().trim();
        if (address.isEmpty()) {
            phoneAccess.turnOff();
            ApiResponse.ok(ctx, view(ctx));
            return;
        }
        if (!pins.status().pinSet()) {
            throw ApiException.conflict(
                    "Please set a PIN first. Anyone who can reach this VPN could otherwise open your budget.");
        }
        // Only an address this PC has right now: nothing else can be listened on, and nothing typed is trusted.
        if (phoneAccess.networks().stream().noneMatch(network -> network.address().equals(address))) {
            throw ApiException.badRequest(
                    "Phone access only works through a VPN that's connected to this PC right now. Please choose one from the list.");
        }
        if (!phoneAccess.turnOn(address)) {
            throw ApiException.conflict(
                    "Another program on this PC is using the port phone access needs, so it wasn't turned on. "
                            + "Please close that program, or try a different VPN.");
        }
        ApiResponse.ok(ctx, view(ctx));
    }
}
