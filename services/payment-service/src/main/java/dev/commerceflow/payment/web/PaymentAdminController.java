package dev.commerceflow.payment.web;

import dev.commerceflow.payment.payment.Attempt;
import dev.commerceflow.payment.payment.ManualReconciliation;
import dev.commerceflow.payment.payment.ManualReconciliation.Outcome;
import dev.commerceflow.payment.payment.ManualReconciliation.Result;
import dev.commerceflow.payment.payment.PaymentQueries;
import dev.commerceflow.web.ApiException;
import dev.commerceflow.web.audit.AuditWriter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin views of payment attempts, and "reconcile now". Every reconcile request is audited,
 * whatever its answer.
 */
@RestController
@RequestMapping("/api/v1/admin/payments")
class PaymentAdminController {

  private static final Set<String> STATUSES =
      Set.of(
          "INITIATED", "SUCCEEDED", "FAILED", "UNKNOWN", "CANCELLED", "REFUND_PENDING", "REFUNDED");
  private static final int LIMIT = 100;

  private final PaymentQueries attempts;
  private final ManualReconciliation reconciliation;
  private final AuditWriter audit;

  PaymentAdminController(
      PaymentQueries attempts, ManualReconciliation reconciliation, AuditWriter audit) {
    this.attempts = attempts;
    this.reconciliation = reconciliation;
    this.audit = audit;
  }

  /** Newest first, at most 100. */
  @GetMapping
  Map<String, List<Attempt>> list(
      @RequestParam(required = false) UUID orderId, @RequestParam(required = false) String status) {
    if (status != null && !STATUSES.contains(status)) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "invalid-status", "Invalid status", "Unknown status " + status);
    }
    return Map.of("items", attempts.list(orderId, status, LIMIT));
  }

  @GetMapping("/{attemptId}")
  Attempt get(@PathVariable UUID attemptId) {
    return attempts.find(attemptId).orElseThrow(() -> ApiException.notFound("payment attempt"));
  }

  /**
   * Brings the attempt's next reconciliation look forward to the reconciler's next round. 409 for a
   * settled attempt, which has nothing left to find. Not one transaction: the look is brought
   * forward in its own, and the audit row is written even when the answer is 404 or 409.
   */
  @PostMapping("/{attemptId}/reconcile")
  ResponseEntity<Void> reconcile(@PathVariable UUID attemptId, @AuthenticationPrincipal Jwt admin) {
    Outcome outcome = reconciliation.requestLook(attemptId);
    audit.record(
        UUID.fromString(admin.getSubject()),
        "ADMIN",
        "PAYMENT_RECONCILE_REQUESTED",
        "payment_attempt",
        attemptId.toString(),
        outcome.result() == Result.SCHEDULED
            ? AuditWriter.Outcome.SUCCESS
            : AuditWriter.Outcome.FAILURE,
        outcome.status() == null
            ? Map.of("result", outcome.result().name())
            : Map.of("result", outcome.result().name(), "status", outcome.status()));
    return switch (outcome.result()) {
      case SCHEDULED -> ResponseEntity.accepted().build();
      case NOT_FOUND -> throw ApiException.notFound("payment attempt");
      case FINAL ->
          throw ApiException.conflict(
              "attempt-final",
              "The attempt is " + outcome.status() + ": there is nothing left to reconcile.");
    };
  }
}
