package org.github.lscoughlin.jeorje.swarm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Zeroconf discovery with multicast DNS (mDNS) and DNS-SD. Each instance registers the service
 * {@value #SERVICE_TYPE}. The service name is the peer id. The text record has the keys {@code peerId},
 * {@code address}, and {@code name}. The class also browses the same type and keeps the instances that
 * it sees.
 */
public final class ZeroconfDiscovery implements PeerDiscovery {
    private static final Logger log = LoggerFactory.getLogger(ZeroconfDiscovery.class);

    /** The DNS-SD service type of a Jeorje instance. */
    public static final String SERVICE_TYPE = "_jeorje._tcp.local.";

    private final JmDNS jmdns;
    private final Map<String, Entry> seen = new ConcurrentHashMap<>();

    /** Opens the mDNS responder on the default network interface and starts to browse. */
    public ZeroconfDiscovery() {
        try {
            this.jmdns = JmDNS.create();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        jmdns.addServiceListener(SERVICE_TYPE, new Listener());
    }

    /** Waits up to the given time for the first answers of the network. Call it once at the start. */
    public void settle(long timeoutMs) {
        jmdns.list(SERVICE_TYPE, timeoutMs);
    }

    @Override
    public void announce(PeerRecord self) {
        var port = portOf(self.address());
        var properties = Map.of("peerId", self.peerId(), "address", self.address(),
                "name", self.name() == null ? "" : self.name());
        try {
            jmdns.registerService(ServiceInfo.create(SERVICE_TYPE, self.peerId(), port, 0, 0, properties));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void withdraw(String peerId) {
        try {
            jmdns.unregisterAllServices();
            jmdns.close();
        } catch (IOException | RuntimeException e) {
            log.atDebug().log(() -> "cannot close zeroconf discovery: " + e.getMessage());
        }
    }

    @Override
    public List<Entry> peers(String exceptPeerId) {
        return seen.values().stream().filter(e -> !e.peerId().equals(exceptPeerId)).toList();
    }

    /**
     * Makes an entry from the data of a resolved service. A service address with a wildcard host cannot
     * be dialed, so the first resolved host replaces it. The result is null if the data is not enough.
     */
    static Entry entryOf(String serviceName, int port, String peerId, String address, String name,
                         List<String> resolvedHosts) {
        var id = peerId == null || peerId.isBlank() ? serviceName : peerId;
        var host = address == null || address.isBlank() ? "" : address.substring(0, Math.max(0, address.lastIndexOf(':')));
        if (host.isBlank() || host.equals("0.0.0.0") || host.equals("::") || host.equals("[::]")) {
            if (resolvedHosts.isEmpty()) return null;
            host = resolvedHosts.getFirst();
        }
        return new Entry(id, host + ":" + port, name == null ? "" : name);
    }

    private static int portOf(String address) {
        return Integer.parseInt(address.substring(address.lastIndexOf(':') + 1));
    }

    private final class Listener implements ServiceListener {
        @Override
        public void serviceAdded(ServiceEvent event) {
            jmdns.requestServiceInfo(event.getType(), event.getName(), true);
        }

        @Override
        public void serviceRemoved(ServiceEvent event) {
            seen.values().removeIf(e -> e.peerId().equals(event.getName()));
        }

        @Override
        public void serviceResolved(ServiceEvent event) {
            var info = event.getInfo();
            var entry = entryOf(event.getName(), info.getPort(), info.getPropertyString("peerId"),
                    info.getPropertyString("address"), info.getPropertyString("name"),
                    List.of(info.getInet4Addresses()).stream().map(a -> a.getHostAddress()).toList());
            if (entry != null) {
                seen.put(entry.peerId(), entry);
                log.atDebug().log(() -> "zeroconf saw " + entry);
            }
        }
    }
}
