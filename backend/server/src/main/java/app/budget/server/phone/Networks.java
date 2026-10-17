package app.budget.server.phone;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The networks this PC is on, for "Phone access" to choose from (spec §10, Phase 12).
 *
 * <p>No VPN is named here: a VPN such as Tailscale or WireGuard is listed like any other network,
 * under the name Windows gives it. What tells the two kinds apart is that a VPN's adapter is a
 * tunnel with no hardware address, while Wi-Fi and Ethernet are network cards that have one.
 * That is a guess, so it only ever errs one way: phone access is offered on tunnels alone, and
 * a VPN that happens to have a hardware address is simply not offered.
 */
public interface Networks {

    /**
     * @param address an IPv4 address of this PC on that network, as text
     * @param name    the network's name as Windows shows it (e.g. "Wi-Fi", "Tailscale")
     * @param tunnel  true when it looks like a VPN tunnel rather than a network card
     */
    record Network(String address, String name, boolean tunnel) {}

    /** The networks that are connected now, VPNs first, then by name. */
    List<Network> current();

    /** True when {@code address} is one of this PC's addresses right now. */
    default boolean has(String address) {
        return current().stream().anyMatch(network -> network.address().equals(address));
    }

    /** The real list, read from the operating system each time it is asked for. */
    static Networks ofThisPc() {
        return SystemNetworks.INSTANCE;
    }

    /** Reads {@link NetworkInterface}; kept apart so tests can give a list of their own. */
    final class SystemNetworks implements Networks {

        static final SystemNetworks INSTANCE = new SystemNetworks();

        private static final Logger log = LoggerFactory.getLogger(SystemNetworks.class);

        private SystemNetworks() {}

        @Override
        public List<Network> current() {
            List<Network> found = new ArrayList<>();
            try {
                for (NetworkInterface face : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (!face.isUp() || face.isLoopback()) {
                        continue;
                    }
                    byte[] hardware = face.getHardwareAddress();
                    boolean tunnel = hardware == null || hardware.length == 0;
                    for (InetAddress address : Collections.list(face.getInetAddresses())) {
                        // IPv4 only: an address a person can read off one screen and type on another.
                        // Link-local (169.254.x.x) is what Windows makes up when a network gave it nothing.
                        if (address instanceof Inet4Address && !address.isLinkLocalAddress() && !address.isLoopbackAddress()) {
                            found.add(new Network(address.getHostAddress(), face.getDisplayName(), tunnel));
                        }
                    }
                }
            } catch (SocketException e) {
                // The list can't be read; the address itself is never logged.
                log.warn("Could not list this PC's networks", e);
            }
            found.sort(Comparator.comparing((Network network) -> !network.tunnel()).thenComparing(Network::name));
            return List.copyOf(found);
        }
    }
}
