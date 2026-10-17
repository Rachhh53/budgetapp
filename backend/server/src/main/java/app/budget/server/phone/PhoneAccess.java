package app.budget.server.phone;

import app.budget.server.auth.PinService;
import app.budget.server.auth.SessionStore;
import app.budget.server.settings.SettingKey;
import app.budget.server.settings.SettingsRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phone access (spec §10, Phase 12): whether the app can be opened from another device, and on
 * which of this PC's VPNs. Off by default; while it is off nothing but this PC can reach the app.
 *
 * <p>The choice is saved as the network address to listen on. The listener follows it: opened
 * when phone access is turned on, closed when it is turned off, and kept in step by {@link #keepUp()},
 * which the app's timer calls every little while. That covers a VPN that is started after Budget,
 * a VPN that reconnects after the laptop moves to another Wi-Fi, and a PC that wakes from sleep.
 *
 * <p>Phone access needs a PIN, always: it can't be turned on without one, the PIN can't be removed
 * while it is on, and should the PIN go some other way ({@code --reset-pin}), phone access goes with it.
 */
public final class PhoneAccess {

    private static final Logger log = LoggerFactory.getLogger(PhoneAccess.class);

    /** Why phone access is on but can't be reached right now. */
    public enum Problem {
        /** Nothing is wrong. */
        NONE,
        /** The chosen VPN isn't connected right now (not running, or still reconnecting). */
        NETWORK_NOT_CONNECTED,
        /** Another program is using the port on that network. */
        PORT_IN_USE
    }

    /**
     * @param on        true when phone access is turned on
     * @param address   the network address it listens on; empty when it is off
     * @param url       what to open on the phone; empty when it is off
     * @param listening true when the phone can reach it right now
     * @param problem   why it can't, when it is on but not listening
     */
    public record Status(boolean on, String address, String url, boolean listening, Problem problem) {}

    private final SettingsRepository settings;
    private final PinService pins;
    private final SessionStore sessions;
    private final Networks networks;
    private final PhoneListener listener;
    private final int port;
    private Problem problem = Problem.NONE;

    /** @param port the port to listen on (fixed, so the address saved on the phone keeps working) */
    public PhoneAccess(
            SettingsRepository settings, PinService pins, SessionStore sessions, Networks networks, PhoneListener listener, int port) {
        this.settings = settings;
        this.pins = pins;
        this.sessions = sessions;
        this.networks = networks;
        this.listener = listener;
        this.port = port;
    }

    public boolean isOn() {
        return !settings.get(SettingKey.PHONE_ACCESS_ADDRESS).isEmpty();
    }

    /**
     * The networks phone access can listen on now: VPNs and other tunnels only. Never Wi-Fi or a network
     * cable (Rachel, 2026-10-17): this PC is a laptop that joins any Wi-Fi it is near, so listening on one
     * would be safe only at home, and nothing should depend on remembering to change a setting elsewhere.
     */
    public List<Networks.Network> networks() {
        return networks.current().stream().filter(Networks.Network::tunnel).toList();
    }

    private boolean isTunnel(String address) {
        return networks().stream().anyMatch(network -> network.address().equals(address));
    }

    public synchronized Status status() {
        String address = settings.get(SettingKey.PHONE_ACCESS_ADDRESS);
        if (address.isEmpty()) {
            return new Status(false, "", "", false, Problem.NONE);
        }
        boolean listening = listener.address().filter(address::equals).isPresent();
        return new Status(true, address, "http://" + address + ":" + listeningPort() + "/", listening,
                listening ? Problem.NONE : problem);
    }

    /**
     * Turns phone access on for {@code address}, or moves it there. The caller has already checked
     * that the address is one of this PC's and that a PIN is set.
     *
     * @return false when the port is taken on that network: phone access then stays as it was
     */
    public synchronized boolean turnOn(String address) {
        String before = settings.get(SettingKey.PHONE_ACCESS_ADDRESS);
        settings.put(SettingKey.PHONE_ACCESS_ADDRESS, address);
        keepUp();
        if (problem == Problem.PORT_IN_USE) {
            settings.put(SettingKey.PHONE_ACCESS_ADDRESS, before);
            keepUp();
            return false;
        }
        return true;
    }

    /** Turns phone access off: stops listening on the network, and every other device is signed out. */
    public synchronized void turnOff() {
        settings.put(SettingKey.PHONE_ACCESS_ADDRESS, "");
        listener.close();
        problem = Problem.NONE;
        sessions.revokeOtherDevices();
    }

    /**
     * Makes the listener match the setting: opens it, closes it, or moves it. Safe to call often.
     * Called at start-up, by the timer, and after anything that replaces the data (a restore, Erase all data).
     */
    public synchronized void keepUp() {
        String address = settings.get(SettingKey.PHONE_ACCESS_ADDRESS);
        if (address.isEmpty()) {
            listener.close();
            problem = Problem.NONE;
            return;
        }
        if (!pins.status().pinSet()) {
            // Never open to the network without a PIN, whatever the setting says.
            turnOff();
            return;
        }
        if (networks.has(address) && !isTunnel(address)) {
            // Saved when Wi-Fi could still be chosen, or the address now belongs to a network card:
            // never listened on. Turned off rather than left waiting, so it can't come back by itself.
            turnOff();
            return;
        }
        if (!networks.has(address)) {
            // Listening on an address the PC no longer has is no use, and it is let go so that
            // it can be opened afresh when the network comes back.
            listener.close();
            problem = Problem.NETWORK_NOT_CONNECTED;
            return;
        }
        if (listener.address().filter(address::equals).isPresent()) {
            problem = Problem.NONE;
            return;
        }
        try {
            listener.open(address, port);
            problem = Problem.NONE;
            log.info("Phone access is listening");
        } catch (Exception e) {
            // Neither the address nor the port is logged: the log stays about the app, not the network.
            log.warn("Phone access could not listen: {}", e.getClass().getSimpleName());
            problem = Problem.PORT_IN_USE;
        }
    }

    /** Stops listening, for when the app closes. The setting is kept for next time. */
    public synchronized void stop() {
        listener.close();
    }

    private int listeningPort() {
        int bound = listener.port();
        return bound > 0 ? bound : port;
    }
}
