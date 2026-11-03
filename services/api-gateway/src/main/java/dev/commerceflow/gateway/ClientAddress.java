package dev.commerceflow.gateway;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The client's address, for per-client rate limits. Normally the TCP peer. When the peer is one of
 * the trusted proxies (the web app's nginx), it is the right-most X-Forwarded-For entry instead:
 * the address that proxy saw and appended. Entries further left came from the client and are
 * ignored, so nobody can choose their own bucket. From any other peer, X-Forwarded-For is ignored
 * entirely.
 *
 * <p>Trusted proxies are configured by host name (Compose service names) or address, and resolved
 * again every 30 s, because a recreated container gets a new address.
 */
final class ClientAddress {

  private static final Logger log = LoggerFactory.getLogger(ClientAddress.class);
  private static final Duration RESOLVE_EVERY = Duration.ofSeconds(30);
  private static final Pattern ADDRESS = Pattern.compile("^[0-9a-fA-F:.]{2,45}$");

  private final List<String> trusted;
  private final Clock clock;
  private volatile Set<InetAddress> resolved = Set.of();
  private volatile Instant resolvedAt = Instant.EPOCH;

  ClientAddress(List<String> trustedProxies, Clock clock) {
    // An unset variable binds as one empty name, which would resolve to localhost: drop blanks.
    this.trusted =
        trustedProxies == null
            ? List.of()
            : trustedProxies.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    this.clock = clock;
  }

  String of(InetAddress peer, String forwardedFor) {
    if (peer == null) {
      return "unknown";
    }
    if (forwardedFor != null && !trusted.isEmpty() && trustedProxies().contains(peer)) {
      String[] hops = forwardedFor.split(",");
      String last = hops[hops.length - 1].trim();
      if (ADDRESS.matcher(last).matches()) {
        return last;
      }
    }
    return peer.getHostAddress();
  }

  private Set<InetAddress> trustedProxies() {
    Instant now = clock.instant();
    if (Duration.between(resolvedAt, now).compareTo(RESOLVE_EVERY) >= 0) {
      Set<InetAddress> fresh = new HashSet<>();
      for (String name : trusted) {
        try {
          fresh.addAll(List.of(InetAddress.getAllByName(name)));
        } catch (UnknownHostException e) {
          // Not running (the web app is optional): nothing from that name is trusted.
          log.debug("Trusted proxy {} does not resolve", name);
        }
      }
      resolved = Set.copyOf(fresh);
      resolvedAt = now;
    }
    return resolved;
  }
}
