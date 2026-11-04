package dev.commerceflow.payment.payment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Read-only views of payment attempts, for the admin API. */
@Component
public class PaymentQueries {

  private final JdbcClient jdbc;

  PaymentQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Newest first.
   *
   * @param orderId only this order's attempt, or null for any
   * @param status only attempts in this status, or null for any
   */
  @Transactional(readOnly = true)
  public List<Attempt> list(UUID orderId, String status, int limit) {
    return jdbc.sql(
            """
            select * from payment_attempts
             where (cast(:order as uuid) is null or order_id = :order)
               and (cast(:status as text) is null or status = :status)
             order by created_at desc, id desc
             limit :limit
            """)
        .param("order", orderId)
        .param("status", status)
        .param("limit", limit)
        .query(Attempt::map)
        .list();
  }

  @Transactional(readOnly = true)
  public Optional<Attempt> find(UUID attemptId) {
    return jdbc.sql("select * from payment_attempts where id = :id")
        .param("id", attemptId)
        .query(Attempt::map)
        .optional();
  }
}
