package dev.commerceflow.sandbox;

import java.time.Duration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SandboxApplication {

  @ConfigurationProperties("sandbox")
  public record SandboxProperties(String apiKey, Duration stall) {}

  public static void main(String[] args) {
    SpringApplication.run(SandboxApplication.class, args);
  }
}
