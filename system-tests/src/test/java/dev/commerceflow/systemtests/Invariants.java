package dev.commerceflow.systemtests;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ARCHITECTURE.md B.3, as predicates over the databases. Each scenario ends by checking them for
 * every order it placed (INV-3 to INV-6) and for all stock (INV-1, INV-2). A violation is reported
 * with the order it concerns, never fixed up.
 */
final class Invariants {

  static final Set<String> TERMINAL = Set.of("CONFIRMED", "FAILED");

  private final Databases db;

  Invariants(Databases db) {
    this.db = db;
  }

  /** The order's customer-visible status, or null if order-service has no such order. */
  String status(UUID order) {
    var rows = db.query("order_db", "select status from orders where id = ?", order);
    return rows.isEmpty() ? null : (String) rows.getFirst().get("status");
  }

  String failureReason(UUID order) {
    var rows = db.query("order_db", "select failure_reason from orders where id = ?", order);
    return rows.isEmpty() ? null : (String) rows.getFirst().get("failure_reason");
  }

  boolean allTerminal(Collection<UUID> orders) {
    return orders.stream().allMatch(o -> TERMINAL.contains(status(o)));
  }

  long countWithStatus(Collection<UUID> orders, String status) {
    return orders.stream().filter(o -> status.equals(status(o))).count();
  }

  List<String> violations(Collection<UUID> orders) {
    List<String> found = new ArrayList<>();
    stock(found);
    for (UUID order : orders) {
      order(order, found);
    }
    return found;
  }

  /** INV-1 and INV-2 for every product. */
  private void stock(List<String> found) {
    for (Map<String, Object> row :
        db.query(
            "inventory_db",
            """
            select product_id from inventory
             where available < 0 or reserved < 0 or committed < 0
                or available + reserved + committed <> total_received - total_written_off
            """)) {
      found.add("INV-1/2: stock not conserved for product " + row.get("product_id"));
    }
  }

  private void order(UUID order, List<String> found) {
    String status = status(order);
    if (status == null) {
      found.add("order " + order + " was accepted but does not exist");
      return;
    }
    if (!TERMINAL.contains(status)) {
      found.add("INV-6: order " + order + " is still " + status);
    }
    List<Map<String, Object>> attempts =
        db.query("payment_db", "select id, status from payment_attempts where order_id = ?", order);
    if (attempts.size() > 1) {
      found.add("INV-3: order " + order + " has " + attempts.size() + " payment attempts");
    }
    String attemptStatus = attempts.isEmpty() ? null : (String) attempts.getFirst().get("status");
    Charges charges =
        attempts.isEmpty() ? Charges.NONE : charges(attempts.getFirst().get("id").toString());
    if (charges.succeeded() > 1) {
      found.add("INV-3: order " + order + " was charged " + charges.succeeded() + " times");
    }
    String reservation = reservation(order);
    switch (status) {
      case "CONFIRMED" -> {
        if (!"SUCCEEDED".equals(attemptStatus) || charges.held() != 1) {
          found.add(
              "INV-4: order "
                  + order
                  + " is CONFIRMED with payment "
                  + attemptStatus
                  + " and "
                  + charges.held()
                  + " charges held");
        }
        if (!"COMMITTED".equals(reservation)) {
          found.add("INV-4: order " + order + " is CONFIRMED with reservation " + reservation);
        }
      }
      case "FAILED" -> {
        if (charges.held() != 0
            || "SUCCEEDED".equals(attemptStatus)
            || "REFUND_PENDING".equals(attemptStatus)) {
          found.add(
              "INV-5: order "
                  + order
                  + " FAILED but holds money (payment "
                  + attemptStatus
                  + ", "
                  + charges.held()
                  + " unrefunded charges)");
        }
        if (reservation != null && !Set.of("RELEASED", "REJECTED").contains(reservation)) {
          found.add("INV-5: order " + order + " FAILED with reservation " + reservation);
        }
      }
      default -> {}
    }
  }

  /**
   * @param succeeded successful charges under the attempt's key
   * @param held those not refunded in full
   */
  record Charges(long succeeded, long held) {
    static final Charges NONE = new Charges(0, 0);
  }

  Charges charges(String idempotencyKey) {
    Map<String, Object> row =
        db.query(
                "sandbox_db",
                """
                select count(*) filter (where status = 'SUCCEEDED') as succeeded,
                       count(*) filter (where status = 'SUCCEEDED'
                                          and refunded_minor < amount_minor) as held
                  from charges where idempotency_key = ?
                """,
                idempotencyKey)
            .getFirst();
    return new Charges(
        ((Number) row.get("succeeded")).longValue(), ((Number) row.get("held")).longValue());
  }

  String reservation(UUID order) {
    var rows =
        db.query("inventory_db", "select status from order_reservations where order_id = ?", order);
    return rows.isEmpty() ? null : (String) rows.getFirst().get("status");
  }

  /** Everything a replay must leave unchanged, for orders that had already finished. */
  Map<String, Object> snapshot(Collection<UUID> finished) {
    Map<String, Object> state = new HashMap<>();
    for (UUID order : finished) {
      state.put("order " + order, status(order) + "/" + reservation(order));
    }
    state.put(
        "charges",
        db.query(
            "sandbox_db",
            "select count(*) as charges, coalesce(sum(refunded_minor), 0) as refunded"
                + " from charges where status = 'SUCCEEDED'"));
    state.put(
        "stock",
        db.query(
            "inventory_db",
            "select sum(available) as a, sum(reserved) as r, sum(committed) as c from inventory"));
    state.put(
        "attempts",
        db.query(
            "payment_db",
            "select status, count(*) as n from payment_attempts group by status order by status"));
    return state;
  }
}
