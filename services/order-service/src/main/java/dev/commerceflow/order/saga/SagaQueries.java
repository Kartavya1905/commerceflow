package dev.commerceflow.order.saga;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Read-only views of saga instances, for the admin API. */
@Component
class SagaQueries {

  record SagaView(
      UUID id,
      UUID orderId,
      String state,
      int stepAttempts,
      OffsetDateTime deadlineAt,
      OffsetDateTime updatedAt) {}

  private final JdbcClient jdbc;
  private final Clock clock;

  SagaQueries(JdbcClient jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /**
   * @param stuck only sagas past their deadline (and not finished)
   */
  @Transactional(readOnly = true)
  List<SagaView> list(boolean stuck, int page, int size) {
    return jdbc.sql(
            """
            select id, order_id, state, step_attempts, deadline_at, updated_at
              from saga_instances
             where not :stuck or deadline_at < :now
             order by updated_at desc, id desc
             limit :size offset :offset
            """)
        .param("stuck", stuck)
        .param("now", OffsetDateTime.now(clock))
        .param("size", size)
        .param("offset", (long) page * size)
        .query(
            (rs, n) ->
                new SagaView(
                    rs.getObject("id", UUID.class),
                    rs.getObject("order_id", UUID.class),
                    rs.getString("state"),
                    rs.getInt("step_attempts"),
                    rs.getObject("deadline_at", OffsetDateTime.class),
                    rs.getObject("updated_at", OffsetDateTime.class)))
        .list();
  }
}
