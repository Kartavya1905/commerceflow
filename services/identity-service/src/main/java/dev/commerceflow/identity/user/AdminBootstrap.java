package dev.commerceflow.identity.user;

import dev.commerceflow.identity.config.IdentityProperties;
import dev.commerceflow.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Creates the first admin from BOOTSTRAP_ADMIN_EMAIL / BOOTSTRAP_ADMIN_PASSWORD when no admin
 * exists. There is no hardcoded admin account, and an existing customer is never promoted.
 */
@Component
class AdminBootstrap implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

  private final UserRepository users;
  private final AccountService accounts;
  private final IdentityProperties properties;

  AdminBootstrap(UserRepository users, AccountService accounts, IdentityProperties properties) {
    this.users = users;
    this.accounts = accounts;
    this.properties = properties;
  }

  @Override
  public void run(ApplicationArguments args) {
    String email = properties.bootstrapAdminEmail();
    String password = properties.bootstrapAdminPassword();
    if (email == null || email.isBlank() || password == null || password.isBlank()) {
      return;
    }
    if (users.existsByRole(Role.ADMIN)) {
      return;
    }
    if (users.existsByEmail(Emails.normalize(email))) {
      log.warn("Bootstrap admin email belongs to an existing non-admin account; not promoting it");
      return;
    }
    try {
      accounts.register(email, password, "Administrator", Role.ADMIN);
      log.info("Created the bootstrap admin account");
    } catch (ApiException e) {
      // Another instance won the race (email-taken) or the password breaks the policy.
      log.warn("Bootstrap admin not created: {}", e.getMessage());
    }
  }
}
