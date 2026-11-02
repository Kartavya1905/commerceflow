package dev.commerceflow.payment.web;

import dev.commerceflow.payment.payment.Attempt;
import dev.commerceflow.web.ApiException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only admin views of payment attempts. Manual reconciliation arrives with Milestone 7. */
@RestController
@RequestMapping("/api/v1/admin/payments")
class PaymentAdminController {

  private static final Set<String> STATUSES =
      Set.of(
          "INITIATED", "SUCCEEDED", "FAILED", "UNKNOWN", "CANCELLED", "REFUND_PENDING", "REFUNDED");
  private static final int LIMIT = 100;

  private final JdbcClient jdbc;

  PaymentAdminController(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Newest first, at most 100. */
  @GetMapping
  @Transactional(readOnly = true)
  Map<String, List<Attempt>> list(
      @RequestParam(required = false) UUID orderId, @RequestParam(required = false) String status) {
    if (status != null && !STATUSES.contains(status)) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "invalid-status", "Invalid status", "Unknown status " + status);
    }
    List<Attempt> items =
        jdbc.sql(
                """
                select * from payment_attempts
                 where (cast(:order as uuid) is null or order_id = :order)
                   and (cast(:status as text) is null or status = :status)
                 order by created_at desc, id desc
                 limit :limit
                """)
            .param("order", orderId)
            .param("status", status)
            .param("limit", LIMIT)
            .query(Attempt::map)
            .list();
    return Map.of("items", items);
  }

  @GetMapping("/{attemptId}")
  @Transactional(readOnly = true)
  Attempt get(@PathVariable UUID attemptId) {
    return jdbc.sql("select * from payment_attempts where id = :id")
        .param("id", attemptId)
        .query(Attempt::map)
        .optional()
        .orElseThrow(() -> ApiException.notFound("payment attempt"));
  }
}
