package dev.commerceflow.gateway;

import java.time.Duration;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * How long the gateway trusts a service's address. Netty's resolver caches a DNS answer for as long
 * as its TTL, and Docker's DNS answers with 600 s. A service whose container is recreated (a
 * deploy, a crash and restart) comes back on a new address, and until then every request to it went
 * to the old one and was refused. The failure-recovery tests found this. Capping the cache at a few
 * seconds costs one local DNS lookup now and then.
 */
@Configuration(proxyBeanMethods = false)
public class UpstreamDnsConfig {

  static final Duration MAX_TTL = Duration.ofSeconds(5);

  @Bean
  HttpClientCustomizer shortUpstreamDnsCache() {
    return client ->
        client.resolver(
            spec -> spec.cacheMaxTimeToLive(MAX_TTL).cacheNegativeTimeToLive(Duration.ZERO));
  }
}
