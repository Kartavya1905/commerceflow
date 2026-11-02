package dev.commerceflow.inventory.reservation;

import dev.commerceflow.inventory.config.InventoryConfig.InventoryProperties;
import dev.commerceflow.inventory.reservation.ReservationDecision.RejectReason;
import dev.commerceflow.inventory.reservation.ReservationDecision.Status;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reserve, commit and release stock for an order. Every operation is idempotent by order id.
 *
 * <p>Locking order, everywhere: first the order's row in {@code order_reservations}, then the
 * {@code inventory} rows of that one order in ascending product id. Two transactions therefore
 * always request locks in the same order and cannot deadlock.
 */
@Service
public class ReservationService {

  private static final Logger log = LoggerFactory.getLogger(ReservationService.class);
  private static final int MAX_LINES = 50;

  public enum ReleaseResult {
    /** Reserved stock went back to available. */
    RELEASED,
    /** Nothing was reserved yet; a tombstone now stops a late reserve from taking stock. */
    TOMBSTONED,
    /** Already released, expired or rejected; nothing to do. */
    ALREADY_RELEASED,
    /** The stock is committed (sold); releasing it would be wrong. */
    NOT_RELEASABLE
  }

  /** Same values as InventoryCommitFailed.reason in the event contract. */
  public enum CommitFailure {
    RESERVATION_NOT_FOUND,
    RESERVATION_EXPIRED
  }

  public record CommitResult(boolean committed, boolean replay, CommitFailure failure) {}

  private final JdbcClient jdbc;
  private final Clock clock;
  private final InventoryProperties properties;
  private final MeterRegistry meters;
  private final JsonMapper json = JsonMapper.builder().build();

  public ReservationService(
      JdbcClient jdbc, Clock clock, InventoryProperties properties, MeterRegistry meters) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.properties = properties;
    this.meters = meters;
  }

  /**
   * All or nothing: either every line is reserved, or none is and the order is rejected with the
   * products that caused it. Calling again for the same order returns the first decision.
   */
  @Transactional
  public ReservationDecision reserve(UUID orderId, List<ReservationLine> requested) {
    List<ReservationLine> lines = sortedDistinct(requested);
    Instant now = clock.instant();

    // Claims the order. A concurrent duplicate blocks here until this transaction ends, then
    // finds the decision; a later duplicate (or a release tombstone) finds it straight away.
    int claimed =
        jdbc.sql(
                """
                insert into order_reservations (order_id, status, expires_at, created_at, updated_at)
                values (:orderId, 'RESERVED', :expiresAt, :now, :now)
                on conflict (order_id) do nothing
                """)
            .param("orderId", orderId)
            .param("expiresAt", ts(now.plus(properties.reservationLongStop())))
            .param("now", ts(now))
            .update();
    if (claimed == 0) {
      count("replayed");
      return find(orderId).orElseThrow().asReplay();
    }

    List<UUID> productIds = lines.stream().map(ReservationLine::productId).toList();
    Map<UUID, Integer> available = new HashMap<>();
    jdbc.sql(
            """
            select product_id, available from inventory
             where product_id in (:ids)
             order by product_id
               for update
            """)
        .param("ids", productIds)
        .query(
            (RowCallbackHandler)
                rs ->
                    available.put(rs.getObject("product_id", UUID.class), rs.getInt("available")));

    List<UUID> unknown = productIds.stream().filter(id -> !available.containsKey(id)).toList();
    if (!unknown.isEmpty()) {
      return reject(orderId, RejectReason.UNKNOWN_PRODUCT, unknown, now);
    }
    List<UUID> shortOf =
        lines.stream()
            .filter(l -> available.get(l.productId()) < l.quantity())
            .map(ReservationLine::productId)
            .toList();
    if (!shortOf.isEmpty()) {
      return reject(orderId, RejectReason.OUT_OF_STOCK, shortOf, now);
    }

    for (ReservationLine line : lines) {
      // The rows are locked and checked, so this cannot fail; the guard is a second line of
      // defence, and the CHECK constraints a third.
      int moved =
          jdbc.sql(
                  """
                  update inventory
                     set available = available - :q, reserved = reserved + :q,
                         version = version + 1, updated_at = :now
                   where product_id = :p and available >= :q
                  """)
              .param("q", line.quantity())
              .param("p", line.productId())
              .param("now", ts(now))
              .update();
      if (moved != 1) {
        throw new IllegalStateException("stock changed under a row lock for " + line.productId());
      }
      jdbc.sql("insert into reservation_lines (order_id, product_id, quantity) values (:o, :p, :q)")
          .param("o", orderId)
          .param("p", line.productId())
          .param("q", line.quantity())
          .update();
    }
    count("reserved");
    return new ReservationDecision(orderId, Status.RESERVED, null, List.of(), lines, false);
  }

  /** Reserved stock becomes committed (sold). Idempotent. */
  @Transactional
  public CommitResult commit(UUID orderId) {
    Optional<Status> status = lockStatus(orderId);
    if (status.isEmpty()) {
      return new CommitResult(false, false, CommitFailure.RESERVATION_NOT_FOUND);
    }
    return switch (status.get()) {
      case COMMITTED -> new CommitResult(true, true, null);
      case RESERVED -> {
        Instant now = clock.instant();
        for (ReservationLine line : lines(orderId)) {
          move(line, "reserved = reserved - :q, committed = committed + :q", "reserved", now);
        }
        setStatus(orderId, Status.COMMITTED, now);
        yield new CommitResult(true, false, null);
      }
      case RELEASED, EXPIRED -> new CommitResult(false, false, CommitFailure.RESERVATION_EXPIRED);
      case REJECTED -> new CommitResult(false, false, CommitFailure.RESERVATION_NOT_FOUND);
    };
  }

  /**
   * Returns reserved stock to available. Idempotent, and safe to call before reserve: it then
   * leaves a tombstone so the late reserve finds a decision and takes nothing.
   */
  @Transactional
  public ReleaseResult release(UUID orderId) {
    Instant now = clock.instant();
    int tombstoned =
        jdbc.sql(
                """
                insert into order_reservations (order_id, status, created_at, updated_at)
                values (:orderId, 'RELEASED', :now, :now)
                on conflict (order_id) do nothing
                """)
            .param("orderId", orderId)
            .param("now", ts(now))
            .update();
    if (tombstoned == 1) {
      return ReleaseResult.TOMBSTONED;
    }
    Status status = lockStatus(orderId).orElseThrow();
    return switch (status) {
      case RESERVED -> {
        returnToAvailable(orderId, now);
        setStatus(orderId, Status.RELEASED, now);
        yield ReleaseResult.RELEASED;
      }
      case RELEASED, EXPIRED, REJECTED -> ReleaseResult.ALREADY_RELEASED;
      case COMMITTED -> {
        log.warn("Release requested for committed order {}; ignoring", orderId);
        yield ReleaseResult.NOT_RELEASABLE;
      }
    };
  }

  /** Reservations past the long-stop, oldest first. Read without locks; see expireOne. */
  @Transactional(readOnly = true)
  public List<UUID> overdue(int limit) {
    return jdbc.sql(
            """
            select order_id from order_reservations
             where status = 'RESERVED' and expires_at < :now
             order by expires_at
             limit :limit
            """)
        .param("now", ts(clock.instant()))
        .param("limit", limit)
        .query(UUID.class)
        .list();
  }

  /**
   * Expires one overdue reservation in its own transaction. One order per transaction keeps the
   * locking order (that order's rows, ascending product id); expiring several orders in one
   * transaction would interleave their rows and could deadlock with a concurrent reserve.
   */
  @Transactional
  public boolean expireOne(UUID orderId) {
    Instant now = clock.instant();
    Optional<UUID> locked =
        jdbc.sql(
                """
                select order_id from order_reservations
                 where order_id = :id and status = 'RESERVED' and expires_at < :now
                   for update skip locked
                """)
            .param("id", orderId)
            .param("now", ts(now))
            .query(UUID.class)
            .optional();
    if (locked.isEmpty()) {
      return false;
    }
    returnToAvailable(orderId, now);
    setStatus(orderId, Status.EXPIRED, now);
    count("expired");
    log.warn("Reservation for order {} passed its long-stop and was expired", orderId);
    return true;
  }

  @Transactional(readOnly = true)
  public Optional<ReservationDecision> find(UUID orderId) {
    return jdbc.sql(
            "select status, reject_reason, rejected_product_ids::text as rejected"
                + " from order_reservations where order_id = :id")
        .param("id", orderId)
        .query(
            (rs, n) ->
                new ReservationDecision(
                    orderId,
                    Status.valueOf(rs.getString("status")),
                    rs.getString("reject_reason") == null
                        ? null
                        : RejectReason.valueOf(rs.getString("reject_reason")),
                    rs.getString("rejected") == null
                        ? List.of()
                        : json.readValue(rs.getString("rejected"), new TypeReference<>() {}),
                    lines(orderId),
                    false))
        .optional();
  }

  private ReservationDecision reject(
      UUID orderId, RejectReason reason, List<UUID> productIds, Instant now) {
    jdbc.sql(
            """
            update order_reservations
               set status = 'REJECTED', reject_reason = :reason,
                   rejected_product_ids = cast(:ids as jsonb), expires_at = null, updated_at = :now
             where order_id = :orderId
            """)
        .param("reason", reason.name())
        .param("ids", json.writeValueAsString(productIds))
        .param("now", ts(now))
        .param("orderId", orderId)
        .update();
    meters
        .counter("inventory.reservations", "outcome", "rejected", "reason", reason.name())
        .increment();
    return new ReservationDecision(orderId, Status.REJECTED, reason, productIds, List.of(), false);
  }

  private void returnToAvailable(UUID orderId, Instant now) {
    for (ReservationLine line : lines(orderId)) {
      move(line, "reserved = reserved - :q, available = available + :q", "reserved", now);
    }
  }

  /** Moves a line's quantity between columns; the guard column must hold at least that much. */
  private void move(ReservationLine line, String assignments, String guardColumn, Instant now) {
    int moved =
        jdbc.sql(
                "update inventory set "
                    + assignments
                    + ", version = version + 1, updated_at = :now where product_id = :p and "
                    + guardColumn
                    + " >= :q")
            .param("q", line.quantity())
            .param("p", line.productId())
            .param("now", ts(now))
            .update();
    if (moved != 1) {
      throw new IllegalStateException(
          "inventory for " + line.productId() + " does not hold the reserved quantity");
    }
  }

  private Optional<Status> lockStatus(UUID orderId) {
    return jdbc.sql("select status from order_reservations where order_id = :id for update")
        .param("id", orderId)
        .query(String.class)
        .optional()
        .map(Status::valueOf);
  }

  private void setStatus(UUID orderId, Status status, Instant now) {
    jdbc.sql(
            "update order_reservations set status = :s, expires_at = null, updated_at = :now"
                + " where order_id = :id")
        .param("s", status.name())
        .param("now", ts(now))
        .param("id", orderId)
        .update();
  }

  /** Lines in ascending product id: the locking order. */
  private List<ReservationLine> lines(UUID orderId) {
    return jdbc.sql(
            "select product_id, quantity from reservation_lines where order_id = :id"
                + " order by product_id")
        .param("id", orderId)
        .query(
            (rs, n) ->
                new ReservationLine(rs.getObject("product_id", UUID.class), rs.getInt("quantity")))
        .list();
  }

  private static List<ReservationLine> sortedDistinct(List<ReservationLine> lines) {
    if (lines == null || lines.isEmpty() || lines.size() > MAX_LINES) {
      throw new IllegalArgumentException("an order needs 1 to " + MAX_LINES + " lines");
    }
    Set<UUID> seen = new HashSet<>();
    for (ReservationLine line : lines) {
      if (!seen.add(line.productId())) {
        throw new IllegalArgumentException("product " + line.productId() + " appears twice");
      }
    }
    return lines.stream().sorted(Comparator.comparing(ReservationLine::productId)).toList();
  }

  private void count(String outcome) {
    meters.counter("inventory.reservations", "outcome", outcome).increment();
  }

  static OffsetDateTime ts(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
