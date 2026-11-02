package dev.commerceflow.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The same images and versions as deploy/compose/docker-compose.yml, so tests run against what the
 * services run against. Import the ones a test needs, e.g.
 * {@code @Import(Containers.Postgres.class)}.
 */
public final class Containers {

  public static final String POSTGRES_IMAGE = "postgres:18.6";
  public static final String REDIS_IMAGE = "redis:8.10.3";

  private Containers() {}

  @TestConfiguration(proxyBeanMethods = false)
  public static class Postgres {
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
      return new PostgreSQLContainer(POSTGRES_IMAGE);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  public static class Redis {
    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
      return new GenericContainer<>(REDIS_IMAGE).withExposedPorts(6379);
    }
  }
}
