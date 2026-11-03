package dev.commerceflow.order.ops;

import dev.commerceflow.messaging.DeadLetters;
import dev.commerceflow.messaging.DeadLetters.Entry;
import dev.commerceflow.messaging.DeadLetters.Replayed;
import dev.commerceflow.messaging.DeadLetters.UnknownTopicException;
import dev.commerceflow.web.ApiException;
import dev.commerceflow.web.audit.AuditWriter;
import dev.commerceflow.web.audit.AuditWriter.Outcome;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin view of every dead-letter topic, and replay. Hosted here because order-service runs the
 * checkout: the topics belong to all the services, and a replay only needs Kafka, since it hands
 * the record back to the topic's own consumer (dedupe included). Every replay is audited.
 */
@RestController
class DeadLetterAdminController {

  record ReplayRequest(@NotNull @Min(0) Integer partition, @NotNull @Min(0) Long offset) {}

  private final DeadLetters deadLetters;
  private final AuditWriter audit;

  DeadLetterAdminController(DeadLetters deadLetters, AuditWriter audit) {
    this.deadLetters = deadLetters;
    this.audit = audit;
  }

  @GetMapping("/api/v1/admin/dlt/{topic}")
  Map<String, List<Entry>> list(
      @PathVariable String topic, @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) {
    try {
      return Map.of("items", deadLetters.list(topic, limit));
    } catch (UnknownTopicException e) {
      throw ApiException.notFound("dead-letter topic");
    }
  }

  @PostMapping("/api/v1/admin/dlt/{topic}/replay")
  @ResponseStatus(HttpStatus.ACCEPTED)
  @Transactional
  Replayed replay(
      @PathVariable String topic,
      @Valid @RequestBody ReplayRequest request,
      @AuthenticationPrincipal Jwt admin) {
    Replayed replayed;
    try {
      replayed =
          deadLetters
              .replay(topic, request.partition(), request.offset())
              .orElseThrow(() -> ApiException.notFound("dead-letter record"));
    } catch (UnknownTopicException e) {
      throw ApiException.notFound("dead-letter topic");
    }
    audit.record(
        UUID.fromString(admin.getSubject()),
        "ADMIN",
        "DLT_REPLAYED",
        "dead_letter",
        topic + "/" + request.partition() + "@" + request.offset(),
        Outcome.SUCCESS,
        Map.of(
            "toTopic", replayed.toTopic(),
            "eventId", String.valueOf(replayed.eventId())));
    return replayed;
  }
}
