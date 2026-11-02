package dev.commerceflow.identity;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The same PostgreSQL version as docker-compose, started once and shared by every test class. */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainer {

  @Bean
  @ServiceConnection
  PostgreSQLContainer postgres() {
    return new PostgreSQLContainer("postgres:18.6");
  }
}
