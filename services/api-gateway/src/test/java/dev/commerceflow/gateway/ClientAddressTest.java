package dev.commerceflow.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClientAddressTest {

  static InetAddress at(String address) throws Exception {
    return InetAddress.getByName(address);
  }

  @Test
  void withoutTrustedProxiesTheHeaderIsIgnored() throws Exception {
    ClientAddress clients = new ClientAddress(List.of(), Clock.systemUTC());
    assertThat(clients.of(at("10.0.0.7"), "1.2.3.4")).isEqualTo("10.0.0.7");
  }

  @Test
  void anUnsetVariableTrustsNobodyNotEvenLocalhost() throws Exception {
    ClientAddress clients = new ClientAddress(Arrays.asList("", " "), Clock.systemUTC());
    assertThat(clients.of(at("127.0.0.1"), "1.2.3.4")).isEqualTo("127.0.0.1");
  }

  @Test
  void behindATrustedProxyTheAddressItSawCounts() throws Exception {
    ClientAddress clients = new ClientAddress(List.of("127.0.0.1"), Clock.systemUTC());
    assertThat(clients.of(at("127.0.0.1"), "203.0.113.9")).isEqualTo("203.0.113.9");
  }

  /** A client cannot pick its bucket: only the right-most hop, appended by the proxy, counts. */
  @Test
  void entriesTheClientWroteItselfAreIgnored() throws Exception {
    ClientAddress clients = new ClientAddress(List.of("127.0.0.1"), Clock.systemUTC());
    assertThat(clients.of(at("127.0.0.1"), "6.6.6.6, 7.7.7.7, 203.0.113.9"))
        .isEqualTo("203.0.113.9");
  }

  @Test
  void anUntrustedPeerCannotForgeTheHeader() throws Exception {
    ClientAddress clients = new ClientAddress(List.of("127.0.0.1"), Clock.systemUTC());
    assertThat(clients.of(at("10.0.0.8"), "203.0.113.9")).isEqualTo("10.0.0.8");
  }

  @Test
  void garbageInTheHeaderFallsBackToThePeer() throws Exception {
    ClientAddress clients = new ClientAddress(List.of("127.0.0.1"), Clock.systemUTC());
    assertThat(clients.of(at("127.0.0.1"), "not an address; drop table")).isEqualTo("127.0.0.1");
    assertThat(clients.of(null, "203.0.113.9")).isEqualTo("unknown");
  }

  @Test
  void aProxyThatDoesNotResolveIsNotTrusted() throws Exception {
    ClientAddress clients = new ClientAddress(List.of("no-such-host.invalid"), Clock.systemUTC());
    assertThat(clients.of(at("10.0.0.7"), "203.0.113.9")).isEqualTo("10.0.0.7");
  }
}
