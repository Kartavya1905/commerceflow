package dev.commerceflow.identity.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("commerceflow.identity")
public record IdentityProperties(
    Path jwtPrivateKeyPath,
    Duration accessTokenTtl,
    Duration refreshTokenTtl,
    int maxFailedLogins,
    Duration lockoutDuration,
    boolean refreshCookieSecure,
    String bootstrapAdminEmail,
    String bootstrapAdminPassword) {}
