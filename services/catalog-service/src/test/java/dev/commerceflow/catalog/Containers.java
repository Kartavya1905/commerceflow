package dev.commerceflow.catalog;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Same image versions as docker-compose. */
public final class Containers {

  private Containers() {}

  @TestConfiguration(proxyBeanMethods = false)
  public static class Postgres {
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
      return new PostgreSQLContainer("postgres:18.6");
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  public static class Redis {
    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
      return new GenericContainer<>("redis:8.10.3").withExposedPorts(6379);
    }
  }
}
