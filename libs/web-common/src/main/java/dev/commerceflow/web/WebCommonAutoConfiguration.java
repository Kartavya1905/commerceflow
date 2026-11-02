package dev.commerceflow.web;

import dev.commerceflow.web.audit.AuditWriter;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Registers the shared web plumbing in every servlet service that depends on this library. */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WebCommonAutoConfiguration {

  /** Runs before Spring Security, so even a 401 carries the correlation id. */
  @Bean
  FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
    FilterRegistrationBean<CorrelationIdFilter> registration =
        new FilterRegistrationBean<>(new CorrelationIdFilter());
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return registration;
  }

  @Bean
  ProblemDetailsAdvice problemDetailsAdvice() {
    return new ProblemDetailsAdvice();
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(JdbcClient.class)
  static class AuditConfiguration {

    @Bean
    AuditWriter auditWriter(DataSource dataSource) {
      return new AuditWriter(JdbcClient.create(dataSource));
    }
  }
}
