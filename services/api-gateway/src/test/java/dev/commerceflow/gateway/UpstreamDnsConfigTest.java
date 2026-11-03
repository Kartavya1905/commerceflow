package dev.commerceflow.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import reactor.netty.http.client.HttpClient;

class UpstreamDnsConfigTest {

  /** A recreated service is on a new address; the gateway must notice within seconds. */
  @Test
  void upstreamAddressesAreCachedForSecondsNotForTheDnsTtl() {
    HttpClient client =
        new UpstreamDnsConfig().shortUpstreamDnsCache().customize(HttpClient.create());

    var resolver = client.configuration().getNameResolverProvider();
    assertThat(resolver).isNotNull();
    assertThat(resolver.cacheMaxTimeToLive()).isEqualTo(Duration.ofSeconds(5));
    assertThat(resolver.cacheNegativeTimeToLive()).isZero();
  }
}
