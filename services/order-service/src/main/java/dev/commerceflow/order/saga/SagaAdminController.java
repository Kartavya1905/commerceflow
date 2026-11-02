package dev.commerceflow.order.saga;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin view of saga instances; "stuck" means past its deadline and not finished. */
@RestController
class SagaAdminController {

  record SagaView(
      UUID id,
      UUID orderId,
      String state,
      int stepAttempts,
      OffsetDateTime deadlineAt,
      OffsetDateTime updatedAt) {}

  private final JdbcClient jdbc;
  private final Clock clock;

  SagaAdminController(JdbcClient jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @GetMapping("/api/v1/admin/sagas")
  @Transactional(readOnly = true)
  Map<String, List<SagaView>> sagas(
      @RequestParam(defaultValue = "false") boolean stuck,
      @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    List<SagaView> items =
        jdbc.sql(
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
    return Map.of("items", items);
  }
}
