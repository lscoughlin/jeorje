package org.github.lscoughlin.jeorje.core.permissions;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy.Decision;

/**
 * The domain policy of {@code webfetch}. It has allow and deny lists. It blocks loopback,
 * link-local, and private addresses. Call {@link #check} again for each redirect target.
 */
public final class NetworkPolicy {

    /** Resolves a host name to addresses. Tests use a fake resolver. */
    @FunctionalInterface
    public interface Resolver {
        List<InetAddress> resolve(String host) throws UnknownHostException;
    }

    /** The result of a check. */
    public record Verdict(Decision decision, String reason) {}

    private final List<String> allow;
    private final List<String> deny;
    private final Resolver resolver;
    private final Set<String> approved = new CopyOnWriteArraySet<>();

    public NetworkPolicy(List<String> allow, List<String> deny, Resolver resolver) {
        this.allow = List.copyOf(allow);
        this.deny = List.copyOf(deny);
        this.resolver = resolver;
    }

    public NetworkPolicy(List<String> allow, List<String> deny) {
        this(allow, deny, host -> List.of(InetAddress.getAllByName(host)));
    }

    /** Remembers a domain that the user approved for this session. */
    public void approve(String host) {
        approved.add(host.toLowerCase(Locale.ROOT));
    }

    /** Checks a URL. Use the same method for the target of each redirect. */
    public Verdict check(String url) {
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return new Verdict(Decision.DENY, "invalid URL");
        }
        var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return new Verdict(Decision.DENY, "scheme not allowed: " + scheme);
        }
        var host = hostOf(uri);
        if (host == null || host.isEmpty()) return new Verdict(Decision.DENY, "URL has no host");
        if (deny.stream().anyMatch(d -> domainMatches(host, d))) {
            return new Verdict(Decision.DENY, "domain is on the deny list: " + host);
        }
        List<InetAddress> addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            return new Verdict(Decision.DENY, "host cannot be resolved: " + host);
        }
        for (var address : addresses) {
            if (isBlocked(address)) {
                return new Verdict(Decision.DENY, "address is blocked: " + host);
            }
        }
        if (allow.stream().anyMatch(d -> domainMatches(host, d))
                || approved.stream().anyMatch(d -> domainMatches(host, d))) {
            return new Verdict(Decision.ALLOW, "domain is allowed: " + host);
        }
        return new Verdict(Decision.ASK, "new domain: " + host);
    }

    /** The lower-case host of a URL, without brackets, or null. */
    public static String hostOf(String url) {
        try {
            return hostOf(URI.create(url.trim()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String hostOf(URI uri) {
        var host = uri.getHost();
        if (host == null) return null;
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        return host.toLowerCase(Locale.ROOT);
    }

    /** True if the host is the domain or a subdomain of it. A leading {@code *.} is allowed. */
    public static boolean domainMatches(String host, String domain) {
        var d = domain.toLowerCase(Locale.ROOT);
        if (d.startsWith("*.")) d = d.substring(2);
        var h = host.toLowerCase(Locale.ROOT);
        return h.equals(d) || h.endsWith("." + d);
    }

    /** True for loopback, link-local, private, and similar addresses. */
    public static boolean isBlocked(InetAddress a) {
        if (a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isAnyLocalAddress() || a.isMulticastAddress()) {
            return true;
        }
        var b = a.getAddress();
        if (a instanceof Inet4Address) {
            var first = b[0] & 0xff;
            var second = b[1] & 0xff;
            return first == 0 || (first == 100 && second >= 64 && second <= 127);
        }
        if (a instanceof Inet6Address) {
            if ((b[0] & 0xfe) == 0xfc) return true;
            var mapped = true;
            for (var i = 0; i < 10; i++) mapped &= b[i] == 0;
            if (mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
                try {
                    return isBlocked(InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]}));
                } catch (UnknownHostException e) {
                    return true;
                }
            }
        }
        return false;
    }
}
