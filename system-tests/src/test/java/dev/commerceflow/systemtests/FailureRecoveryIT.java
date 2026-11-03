package dev.commerceflow.systemtests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The Milestone 7 failure scenarios (ROADMAP.md), against the real stack started with {@code
 * scripts/dev-up.sh --apps --chaos}. Each one breaks something for real (a killed container, a
 * stopped broker, a cut database link, duplicated and replayed messages, injected consumer faults),
 * lets it recover without any manual step, and then checks every invariant from ARCHITECTURE.md B.3
 * for the orders it placed. Numbers go to {@link Results}.
 *
 * <p>NFR-04: every order is terminal within 2 minutes of the last dependency recovering
 * (reconciliation excepted, which has its own window).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FailureRecoveryIT {

  static final Duration NFR_04 = Duration.ofMinutes(2);

  static Stack stack;
  static Databases db;
  static Invariants invariants;
  static Results results;
  static String admin;
  static String customer;

  /** Plenty of stock, so only the failure under test can make an order fail. */
  static UUID plentiful;

  /** Where the checkout topics ended when this run began; the replay scenario starts here. */
  static Map<TopicPartition, Long> runStart;

  /** Every order this run placed; each scenario's invariants also cover the earlier ones. */
  static final List<UUID> placed = Collections.synchronizedList(new ArrayList<>());

  @BeforeAll
  static void stack() {
    stack = new Stack();
    db = new Databases(stack);
    invariants = new Invariants(db);
    results = new Results(stack);
    assertThat(stack.toxiproxyRunning())
        .as("Toxiproxy on :8474; start the stack with scripts/dev-up.sh --apps --chaos")
        .isTrue();
    stack.awaitHealthy(
        "api-gateway", "order-service", "inventory-service", "payment-service", "payment-sandbox");
    stack.sandboxFault("NONE");
    stack.orderDatabaseLink(true);
    admin = stack.adminToken();
    customer = stack.newCustomer();
    plentiful = stack.product(admin, 1500, 100_000);
    runStart = KafkaTools.endOffsets();
  }

  @AfterEach
  void putThingsBack() {
    // Whatever a scenario broke is restored, even when it failed half-way.
    stack.sandboxFault("NONE");
    stack.orderDatabaseLink(true);
  }

  // --- Scenarios -----------------------------------------------------------------------------

  @Test
  @Order(1)
  void inventoryKilledMidCheckout() throws Exception {
    scenario(
        "inventory-killed",
        record -> {
          List<UUID> orders = place(20, "tok_success");
          stack.docker("kill", Stack.container("inventory-service"));
          Instant killed = Instant.now();
          hold(Duration.ofSeconds(20));
          stack.docker("start", Stack.container("inventory-service"));
          stack.awaitHealthy("inventory-service");
          Instant recovered = Instant.now();

          record.put("orders", orders.size());
          record.put("downSeconds", seconds(killed, recovered));
          record.put("terminalSecondsAfterRecovery", awaitTerminal(orders, recovered, NFR_04));
          assertThat(invariants.countWithStatus(orders, "CONFIRMED")).isEqualTo(orders.size());
        });
  }

  /** The roadmap's lost acknowledgement: the charge happened, its answer never came back. */
  @Test
  @Order(2)
  void lostAcknowledgementIsReconciled() throws Exception {
    scenario(
        "lost-ack-reconciled",
        record -> {
          double before = reconciled();
          stack.sandboxFault("CHARGE_THEN_STALL");
          UUID order = place(1, "tok_success").getFirst();
          await()
              .atMost(Duration.ofSeconds(60))
              .until(() -> "UNKNOWN".equals(attemptStatus(order)));
          stack.sandboxFault("NONE");
          Instant unknownAt = Instant.now();
          // Reconciliation has its own bound: the first look is due 0-30 s after the timeout.
          record.put(
              "confirmedSecondsAfterUnknown",
              awaitTerminal(List.of(order), unknownAt, Duration.ofMinutes(3)));
          assertThat(invariants.status(order)).isEqualTo("CONFIRMED");
          assertThat(chargesFor(order)).isEqualTo(1);
          double counted = reconciled() - before;
          record.put("reconciledCounter", counted);
          assertThat(counted).isEqualTo(1);
        });
  }

  /**
   * Payment-service dies after the provider charged and before it recorded the charge. On restart,
   * the redelivered command (or the reconciler) finds that same charge: one charge.
   */
  @Test
  @Order(3)
  void paymentKilledAfterChargeBeforeAck() throws Exception {
    scenario(
        "payment-killed-after-charge",
        record -> {
          stack.sandboxFault("CHARGE_THEN_STALL");
          UUID order = place(1, "tok_success").getFirst();
          // The charge exists at the provider while its response is held back: kill now.
          await()
              .atMost(Duration.ofSeconds(60))
              .pollInterval(Duration.ofMillis(100))
              .until(() -> chargesFor(order) == 1);
          stack.docker("kill", Stack.container("payment-service"));
          record.put("attemptAtKill", attemptStatus(order));
          stack.sandboxFault("NONE");
          stack.docker("start", Stack.container("payment-service"));
          stack.awaitHealthy("payment-service");
          Instant recovered = Instant.now();

          record.put(
              "terminalSecondsAfterRecovery",
              awaitTerminal(List.of(order), recovered, Duration.ofMinutes(3)));
          assertThat(invariants.status(order)).isEqualTo("CONFIRMED");
          assertThat(chargesFor(order)).isEqualTo(1);
        });
  }

  /** NFR-02: every command and event delivered three times, and still one charge per order. */
  @Test
  @Order(4)
  void everyMessageDeliveredThreeTimes() throws Exception {
    scenario(
        "deliver-3x",
        record -> {
          List<UUID> paid;
          List<UUID> declined;
          try (KafkaTools.Duplicator duplicator = new KafkaTools.Duplicator()) {
            paid = place(10, "tok_success");
            declined = place(5, "tok_decline");
            List<UUID> all = new ArrayList<>(paid);
            all.addAll(declined);
            record.put("terminalSeconds", awaitTerminal(all, Instant.now(), Duration.ofMinutes(3)));
            // Let the last copies land before counting what they changed.
            await().atMost(Duration.ofMinutes(2)).until(() -> KafkaTools.lag() == 0);
            record.put("copiesDelivered", duplicator.copies());
            assertThat(duplicator.copies()).isPositive();
          }
          assertThat(invariants.countWithStatus(paid, "CONFIRMED")).isEqualTo(paid.size());
          assertThat(invariants.countWithStatus(declined, "FAILED")).isEqualTo(declined.size());
          for (UUID order : paid) {
            assertThat(chargesFor(order)).as("charges for %s", order).isEqualTo(1);
          }
          record.put("orders", paid.size() + declined.size());
        });
  }

  /** NFR-01: 100 buyers race for the last unit; exactly one gets it. */
  @Test
  @Order(5)
  void oneHundredOrdersForTheLastUnit() throws Exception {
    scenario(
        "last-unit-race",
        record -> {
          UUID lastUnit = stack.product(admin, 990, 1);
          List<UUID> orders = new ArrayList<>();
          try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<UUID>> racing = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
              racing.add(pool.submit(() -> stack.placeOrder(customer, lastUnit, 1, "tok_success")));
            }
            for (Future<UUID> f : racing) {
              orders.add(f.get());
            }
          }
          placed.addAll(orders);
          record.put(
              "terminalSeconds", awaitTerminal(orders, Instant.now(), Duration.ofMinutes(3)));
          assertThat(invariants.countWithStatus(orders, "CONFIRMED")).isEqualTo(1);
          assertThat(invariants.countWithStatus(orders, "FAILED")).isEqualTo(99);
          assertThat(orders.stream().filter(o -> "FAILED".equals(invariants.status(o))))
              .allMatch(o -> "OUT_OF_STOCK".equals(invariants.failureReason(o)));
          var stock =
              db.query(
                      "inventory_db",
                      "select available, reserved, committed from inventory where product_id = ?",
                      lastUnit)
                  .getFirst();
          assertThat(stock).containsEntry("available", 0).containsEntry("committed", 1);
          record.put("confirmed", 1);
          record.put("failedOutOfStock", 99);
        });
  }

  /** Toxiproxy cuts order-service off from Postgres for 30 s; it recovers without a restart. */
  @Test
  @Order(6)
  void orderDatabaseCutFor30Seconds() throws Exception {
    scenario(
        "postgres-cut",
        record -> {
          String startedBefore = stack.startedAt("order-service");
          List<UUID> before = place(5, "tok_success");
          stack.orderDatabaseLink(false);
          Instant cut = Instant.now();
          List<Integer> statuses = new ArrayList<>();
          List<UUID> acceptedDuring = new ArrayList<>();
          while (Duration.between(cut, Instant.now()).compareTo(Duration.ofSeconds(30)) < 0) {
            Stack.Reply reply = stack.tryPlaceOrder(customer, plentiful, 1, "tok_success");
            statuses.add(reply.status());
            if (reply.status() == 202) {
              acceptedDuring.add(UUID.fromString(reply.body().path("id").asString()));
            }
            hold(Duration.ofSeconds(2));
          }
          stack.orderDatabaseLink(true);
          Instant recovered = Instant.now();
          placed.addAll(acceptedDuring);
          // The pool needs a moment to reconnect. A client retries a 503 (it carries
          // Retry-After), so the time until one is accepted again is the recovery time.
          List<UUID> after = new ArrayList<>();
          List<Integer> statusesAfter = new ArrayList<>();
          await()
              .atMost(Duration.ofSeconds(60))
              .pollInterval(Duration.ofSeconds(1))
              .until(
                  () -> {
                    Stack.Reply reply = stack.tryPlaceOrder(customer, plentiful, 1, "tok_success");
                    statusesAfter.add(reply.status());
                    if (reply.status() == 202) {
                      after.add(UUID.fromString(reply.body().path("id").asString()));
                    }
                    return !after.isEmpty();
                  });
          record.put("secondsUntilAcceptingAgain", seconds(recovered, Instant.now()));
          record.put("statusesAfterRecovery", statusesAfter);
          placed.addAll(after);
          after.addAll(place(4, "tok_success"));
          assertThat(statusesAfter).allMatch(s -> s == 503 || s == 202);

          List<UUID> all = new ArrayList<>(before);
          all.addAll(acceptedDuring);
          all.addAll(after);
          record.put("statusesDuringOutage", statuses);
          record.put("terminalSecondsAfterRecovery", awaitTerminal(all, recovered, NFR_04));
          assertThat(statuses)
              .as("no order is accepted while its database is gone")
              .doesNotContain(202);
          assertThat(statuses).as("503 while the database is gone").allMatch(s -> s == 503);
          assertThat(stack.startedAt("order-service"))
              .as("recovered without a restart")
              .isEqualTo(startedBefore);
          // No partial orders: every order has its lines and its saga.
          assertThat(
                  db.count(
                      "order_db",
                      """
                      select count(*) from orders o
                       where not exists (select 1 from order_items i where i.order_id = o.id)
                          or not exists (select 1 from saga_instances s where s.order_id = o.id)
                      """))
              .isZero();
          assertThat(invariants.countWithStatus(all, "CONFIRMED")).isEqualTo(all.size());
        });
  }

  /** Kafka is stopped for 60 s while orders keep arriving; nothing is lost, only delayed. */
  @Test
  @Order(7)
  void kafkaStoppedFor60Seconds() throws Exception {
    scenario(
        "kafka-stopped",
        record -> {
          stack.docker("stop", Stack.container("kafka"));
          Instant stopped = Instant.now();
          List<UUID> during = new ArrayList<>();
          double oldest = 0;
          while (Duration.between(stopped, Instant.now()).compareTo(Duration.ofSeconds(60)) < 0) {
            during.addAll(place(1, "tok_success"));
            oldest = Math.max(oldest, outboxAge());
            hold(Duration.ofSeconds(5));
          }
          stack.docker("start", Stack.container("kafka"));
          stack.awaitHealthy("kafka");
          Instant recovered = Instant.now();

          record.put("ordersDuringOutage", during.size());
          record.put("maxOutboxAgeSeconds", oldest);
          record.put("terminalSecondsAfterRecovery", awaitTerminal(during, recovered, NFR_04));
          await().atMost(Duration.ofMinutes(1)).until(() -> outboxAge() == 0);
          assertThat(oldest).as("the backlog was visible while Kafka was down").isGreaterThan(30);
          assertThat(invariants.countWithStatus(during, "CONFIRMED")).isEqualTo(during.size());
        });
  }

  /**
   * Every command and event of the run so far, delivered again (from the topic offsets the run
   * started at; copies are not replayed again, so runs stay comparable): no effect.
   */
  @Test
  @Order(8)
  void replayEveryRecordOfTheRun() throws Exception {
    scenario(
        "replay-run",
        record -> {
          await().atMost(Duration.ofMinutes(2)).until(() -> KafkaTools.lag() == 0);
          List<UUID> finished =
              placed.stream()
                  .filter(o -> Invariants.TERMINAL.contains(invariants.status(o)))
                  .toList();
          Map<String, Object> before = invariants.snapshot(finished);
          long dltBefore = KafkaTools.deadLettered();
          double consumedBefore = consumed();

          Instant started = Instant.now();
          long replayed = KafkaTools.replay(runStart);
          // Every replayed record has exactly one consumer group: wait until each was handled.
          await()
              .atMost(Duration.ofMinutes(5))
              .until(() -> consumed() - consumedBefore >= replayed && KafkaTools.lag() == 0);
          record.put("recordsConsumed", consumed() - consumedBefore);
          record.put("recordsReplayed", replayed);
          record.put("secondsToConsume", seconds(started, Instant.now()));

          assertThat(invariants.snapshot(finished)).isEqualTo(before);
          long parked = KafkaTools.deadLettered() - dltBefore;
          record.put("newlyDeadLettered", parked);
          assertThat(parked).isZero();
        });
  }

  /** 5 s added to every record and 30 % of handling attempts failing, in all three consumers. */
  @Test
  @Order(9)
  void slowAndUnreliableConsumers() throws Exception {
    scenario(
        "chaos-consumers",
        record -> {
          String[] consumers = {"order-service", "inventory-service", "payment-service"};
          try {
            recreate(
                Map.of("CHAOS_CONSUMER_DELAY", "5s", "CHAOS_CONSUMER_FAILURE_RATE", "0.3"),
                consumers);
            // Fresh containers: their counters start from zero now.
            double injectedBefore = injectedFailures();
            List<UUID> paid = place(8, "tok_success");
            List<UUID> declined = place(2, "tok_decline");
            List<UUID> all = new ArrayList<>(paid);
            all.addAll(declined);
            record.put(
                "terminalSeconds", awaitTerminal(all, Instant.now(), Duration.ofMinutes(10)));
            record.put("injectedFailures", injectedFailures() - injectedBefore);
            // Under these faults an order may end either way; what must hold is the invariants.
            record.put("confirmed", invariants.countWithStatus(paid, "CONFIRMED"));
            assertThat(invariants.countWithStatus(declined, "FAILED")).isEqualTo(declined.size());
            assertThat(injectedFailures() - injectedBefore).isPositive();
          } finally {
            recreate(Map.of(), consumers);
          }
        });
  }

  // --- Harness -------------------------------------------------------------------------------

  interface Body {
    void run(Map<String, Object> record) throws Exception;
  }

  /** Runs one scenario, then checks every invariant for all orders so far, and records it. */
  void scenario(String name, Body body) throws Exception {
    Map<String, Object> record = results.start(name);
    Instant started = Instant.now();
    try {
      body.run(record);
      List<String> violations = invariants.violations(placed);
      record.put("invariantViolations", violations);
      assertThat(violations).as("INV-1..INV-6 after %s", name).isEmpty();
      record.put("passed", true);
    } catch (Throwable t) {
      record.put("passed", false);
      record.put("error", String.valueOf(t.getMessage()).lines().findFirst().orElse(""));
      throw t;
    } finally {
      record.put("seconds", seconds(started, Instant.now()));
      record.put("ordersSoFar", placed.size());
      results.write(record);
    }
  }

  List<UUID> place(int count, String paymentToken) {
    List<UUID> orders = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      orders.add(stack.placeOrder(customer, plentiful, 1, paymentToken));
    }
    placed.addAll(orders);
    return orders;
  }

  /**
   * @return seconds from {@code since} until every order was terminal
   */
  double awaitTerminal(List<UUID> orders, Instant since, Duration bound) {
    await()
        .atMost(bound.plus(Duration.between(since, Instant.now())))
        .pollInterval(Duration.ofSeconds(1))
        .until(() -> invariants.allTerminal(orders));
    return seconds(since, Instant.now());
  }

  /** Recreates services with these chaos settings (empty: off), and waits for them. */
  void recreate(Map<String, String> chaos, String... services) {
    String[] args = new String[services.length + 3];
    args[0] = "up";
    args[1] = "-d";
    args[2] = "--no-deps";
    System.arraycopy(services, 0, args, 3, services.length);
    stack.compose(chaos, args);
    stack.awaitHealthy(services);
  }

  /**
   * An outage lasts a fixed time by definition; this is the one place the tests wait on the clock
   * rather than on a condition.
   */
  static void hold(Duration outage) throws InterruptedException {
    Thread.sleep(outage);
  }

  static double seconds(Instant from, Instant to) {
    return Duration.between(from, to).toMillis() / 1000.0;
  }

  String attemptStatus(UUID order) {
    var rows =
        db.query("payment_db", "select status from payment_attempts where order_id = ?", order);
    return rows.isEmpty() ? null : (String) rows.getFirst().get("status");
  }

  long chargesFor(UUID order) {
    var rows = db.query("payment_db", "select id from payment_attempts where order_id = ?", order);
    return rows.isEmpty()
        ? 0
        : invariants.charges(rows.getFirst().get("id").toString()).succeeded();
  }

  double reconciled() {
    return stack.metric(Stack.PAYMENT, "payment_reconciled_total", "outcome=\"SUCCEEDED\"");
  }

  double outboxAge() {
    return stack.metric(Stack.ORDER, "outbox_oldest_unpublished_age_seconds", null);
  }

  /** Records the three checkout consumers have handled, applied or recognised as duplicates. */
  double consumed() {
    double total = 0;
    for (String url : List.of(Stack.ORDER, "http://localhost:8084", Stack.PAYMENT)) {
      total += stack.metric(url, "messaging_consumed_total", null);
    }
    return total;
  }

  double injectedFailures() {
    double total = 0;
    for (String url : List.of(Stack.ORDER, "http://localhost:8084", Stack.PAYMENT)) {
      double value = stack.metric(url, "messaging_chaos_failures_total", null);
      total += Double.isNaN(value) ? 0 : value;
    }
    return total;
  }
}
