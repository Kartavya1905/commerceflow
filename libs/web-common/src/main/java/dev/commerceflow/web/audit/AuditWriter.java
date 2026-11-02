package dev.commerceflow.web.audit;

import dev.commerceflow.web.CorrelationIdFilter;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Appends to the service's own {@code audit_events} table.
 *
 * <p>It uses the caller's transaction (JdbcClient joins the JPA transaction's connection), so an
 * audit row exists if and only if the action it describes was committed. Callers must never put
 * secrets in {@code details}: no passwords, tokens, or full email addresses.
 */
public class AuditWriter {

  public enum Outcome {
    SUCCESS,
    FAILURE,
    DENIED
  }

  private final JdbcClient jdbc;
  private final JsonMapper json = JsonMapper.builder().build();

  public AuditWriter(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void record(
      UUID actorId,
      String actorRole,
      String action,
      String resourceType,
      String resourceId,
      Outcome outcome,
      Map<String, ?> details) {
    jdbc.sql(
            """
            insert into audit_events
              (actor_id, actor_role, action, resource_type, resource_id, outcome, correlation_id,
               details)
            values
              (:actorId, :actorRole, :action, :resourceType, :resourceId, :outcome,
               :correlationId, cast(:details as jsonb))
            """)
        .param("actorId", actorId)
        .param("actorRole", actorRole)
        .param("action", action)
        .param("resourceType", resourceType)
        .param("resourceId", resourceId)
        .param("outcome", outcome.name())
        .param("correlationId", CorrelationIdFilter.current())
        .param("details", json.writeValueAsString(details == null ? Map.of() : details))
        .update();
  }
}
