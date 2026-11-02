package dev.commerceflow.sandbox;

import dev.commerceflow.sandbox.SandboxApplication.SandboxProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A small card-payment provider, modelled on how real ones behave.
 *
 * <p>Outcome by payment token:
 *
 * <ul>
 *   <li>{@code tok_success}: charged, 200.
 *   <li>{@code tok_decline}: declined (card_declined), 402.
 *   <li>{@code tok_insufficient}: declined (insufficient_funds), 402.
 *   <li>{@code tok_timeout}: <b>charged</b>, then the response is held past the caller's timeout.
 *       The caller cannot know it succeeded; a lookup by idempotency key can.
 *   <li>{@code tok_unknown}: 503 <b>without</b> charging. The caller cannot know either; a lookup
 *       finds nothing.
 * </ul>
 *
 * <p>The same Idempotency-Key always returns the first result and never charges twice.
 */
@RestController
class SandboxController {

  /** Fault modes for resilience tests, overriding the token behaviour until reset. */
  enum Fault {
    NONE,
    /** Every charge request fails with 503 before charging. */
    FAIL_ALL,
    /** Charge, then hold the response past the caller's timeout (lost acknowledgement). */
    CHARGE_THEN_STALL
  }

  record ChargeRequest(
      @NotNull @Positive Long amountMinor,
      @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
      @NotBlank String paymentToken) {}

  record RefundRequest(@NotBlank String chargeId, @NotNull @Positive Long amountMinor) {}

  record Charge(
      String id,
      String idempotencyKey,
      long amountMinor,
      String currency,
      String status,
      String declineCode,
      long refundedMinor) {}

  private static final String ALPHABET =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
  private static final SecureRandom RANDOM = new SecureRandom();

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final SandboxProperties properties;
  private final AtomicReference<Fault> fault = new AtomicReference<>(Fault.NONE);

  SandboxController(JdbcClient jdbc, TransactionTemplate tx, SandboxProperties properties) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.properties = properties;
  }

  @PostMapping("/v1/charges")
  ResponseEntity<?> charge(
      @RequestHeader("Idempotency-Key") @NotBlank String key,
      @Valid @RequestBody ChargeRequest request) {
    // A repeated key gets the stored answer: this is the provider-side half of INV-3.
    Optional<Charge> existing = findByKey(key);
    if (existing.isPresent()) {
      return answer(existing.get());
    }
    Fault mode = fault.get();
    if (mode == Fault.FAIL_ALL || "tok_unknown".equals(request.paymentToken())) {
      return ResponseEntity.status(503).body(Map.of("error", "provider_unavailable"));
    }
    String status;
    String declineCode = null;
    switch (request.paymentToken()) {
      case "tok_success", "tok_timeout" -> status = "SUCCEEDED";
      case "tok_decline" -> {
        status = "DECLINED";
        declineCode = "card_declined";
      }
      case "tok_insufficient" -> {
        status = "DECLINED";
        declineCode = "insufficient_funds";
      }
      default -> {
        return ResponseEntity.badRequest().body(Map.of("error", "invalid_payment_token"));
      }
    }
    Charge charge = insertOnce(key, request, status, declineCode);
    if ("tok_timeout".equals(request.paymentToken()) || mode == Fault.CHARGE_THEN_STALL) {
      sleep(properties.stall().toMillis());
    }
    return answer(charge);
  }

  @GetMapping("/v1/charges")
  Map<String, List<Charge>> lookup(@RequestParam("idempotency_key") String key) {
    return Map.of("data", findByKey(key).map(List::of).orElse(List.of()));
  }

  @GetMapping("/v1/charges/{id}")
  ResponseEntity<Charge> get(@PathVariable String id) {
    return jdbc.sql("select * from charges where id = ?")
        .param(id)
        .query(SandboxController::map)
        .optional()
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
  }

  /** Full refunds only, which is all CommerceFlow needs. Idempotent by key. */
  @PostMapping("/v1/refunds")
  ResponseEntity<?> refund(
      @RequestHeader("Idempotency-Key") @NotBlank String key,
      @Valid @RequestBody RefundRequest request) {
    Optional<Map<String, Object>> existing = findRefund(key);
    if (existing.isPresent()) {
      return ResponseEntity.ok(existing.get());
    }
    try {
      return refundOnce(key, request);
    } catch (DuplicateKeyException raced) {
      // A concurrent request with the same key committed first: answer with its refund.
      return ResponseEntity.ok(findRefund(key).orElseThrow());
    }
  }

  private ResponseEntity<?> refundOnce(String key, RefundRequest request) {
    return tx.execute(
        status -> {
          Optional<Charge> charge =
              jdbc.sql("select * from charges where id = ? for update")
                  .param(request.chargeId())
                  .query(SandboxController::map)
                  .optional();
          if (charge.isEmpty() || !"SUCCEEDED".equals(charge.get().status())) {
            return ResponseEntity.status(404).body(Map.of("error", "no_such_charge"));
          }
          if (charge.get().refundedMinor() + request.amountMinor() > charge.get().amountMinor()) {
            return ResponseEntity.status(409).body(Map.of("error", "refund_exceeds_charge"));
          }
          String id = "re_" + randomId();
          jdbc.sql(
                  "insert into refunds (id, idempotency_key, charge_id, amount_minor, created_at)"
                      + " values (?, ?, ?, ?, ?)")
              .params(id, key, request.chargeId(), request.amountMinor(), now())
              .update();
          jdbc.sql("update charges set refunded_minor = refunded_minor + ? where id = ?")
              .params(request.amountMinor(), request.chargeId())
              .update();
          return ResponseEntity.ok(findRefund(key).orElseThrow());
        });
  }

  @PostMapping("/sandbox/faults")
  Map<String, String> setFault(@RequestBody Map<String, String> body) {
    fault.set(Fault.valueOf(body.getOrDefault("mode", "NONE")));
    return Map.of("mode", fault.get().name());
  }

  /** Inserts the charge unless a concurrent request with the same key won; returns the winner. */
  private Charge insertOnce(String key, ChargeRequest request, String status, String declineCode) {
    jdbc.sql(
            """
            insert into charges (id, idempotency_key, amount_minor, currency, payment_token, status,
                                 decline_code, created_at)
            values (?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (idempotency_key) do nothing
            """)
        .params(
            "ch_" + randomId(),
            key,
            request.amountMinor(),
            request.currency(),
            request.paymentToken(),
            status,
            declineCode,
            now())
        .update();
    return findByKey(key).orElseThrow();
  }

  private Optional<Charge> findByKey(String key) {
    return jdbc.sql("select * from charges where idempotency_key = ?")
        .param(key)
        .query(SandboxController::map)
        .optional();
  }

  private Optional<Map<String, Object>> findRefund(String key) {
    return jdbc.sql("select id, charge_id, amount_minor from refunds where idempotency_key = ?")
        .param(key)
        .query(
            (rs, n) ->
                Map.<String, Object>of(
                    "id", rs.getString("id"),
                    "chargeId", rs.getString("charge_id"),
                    "amountMinor", rs.getLong("amount_minor")))
        .optional();
  }

  private static ResponseEntity<Charge> answer(Charge charge) {
    return ResponseEntity.status("SUCCEEDED".equals(charge.status()) ? 200 : 402).body(charge);
  }

  private static Charge map(ResultSet rs, int row) throws SQLException {
    return new Charge(
        rs.getString("id"),
        rs.getString("idempotency_key"),
        rs.getLong("amount_minor"),
        rs.getString("currency"),
        rs.getString("status"),
        rs.getString("decline_code"),
        rs.getLong("refunded_minor"));
  }

  private static String randomId() {
    StringBuilder id = new StringBuilder(24);
    for (int i = 0; i < 24; i++) {
      id.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
    }
    return id.toString();
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
