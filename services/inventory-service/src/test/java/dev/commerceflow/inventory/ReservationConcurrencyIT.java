package dev.commerceflow.inventory;

import static dev.commerceflow.inventory.InventoryFixtures.assertInvariantsHold;
import static dev.commerceflow.inventory.InventoryFixtures.productWithStock;
import static dev.commerceflow.inventory.InventoryFixtures.stockOf;
import static org.assertj.core.api.Assertions.assertThat;

import dev.commerceflow.inventory.reservation.ReservationDecision;
import dev.commerceflow.inventory.reservation.ReservationDecision.RejectReason;
import dev.commerceflow.inventory.reservation.ReservationDecision.Status;
import dev.commerceflow.inventory.reservation.ReservationLine;
import dev.commerceflow.inventory.reservation.ReservationService;
import dev.commerceflow.inventory.stock.StockService;
import dev.commerceflow.inventory.stock.StockService.Stock;
import dev.commerceflow.testsupport.Containers;
import dev.commerceflow.testsupport.TestTokens;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * NFR-01: no overselling under contention. All requests start together behind a latch, on virtual
 * threads, so they really do race for the same rows.
 */
@SpringBootTest
@Import({Containers.Postgres.class, Containers.Kafka.class, TestTokens.class})
class ReservationConcurrencyIT {

  @Autowired ReservationService reservations;
  @Autowired StockService stock;
  @Autowired JdbcClient jdbc;

  @AfterEach
  void invariantsHold() {
    assertInvariantsHold(jdbc);
  }

  /** 100 buyers, 1 unit, repeated 20 times: exactly one wins every time. */
  @RepeatedTest(20)
  void oneHundredBuyersForTheLastUnitProduceExactlyOneReservation() throws Exception {
    UUID product = productWithStock(stock, 1);

    List<ReservationDecision> decisions =
        race(
            100,
            i ->
                () ->
                    reservations.reserve(
                        UUID.randomUUID(), List.of(new ReservationLine(product, 1))));

    assertThat(decisions).filteredOn(d -> d.status() == Status.RESERVED).hasSize(1);
    assertThat(decisions)
        .filteredOn(d -> d.status() == Status.REJECTED)
        .hasSize(99)
        .allSatisfy(d -> assertThat(d.rejectReason()).isEqualTo(RejectReason.OUT_OF_STOCK));
    Stock after = stockOf(stock, product);
    assertThat(after.available()).isZero();
    assertThat(after.reserved()).isEqualTo(1);
  }

  /**
   * Orders whose lines arrive in opposite orders, run in parallel 200 times. Without sorting the
   * rows before locking them, Postgres would detect deadlocks and abort one transaction.
   */
  @Test
  void multiLineOrdersInOppositeOrderNeverDeadlock() throws Exception {
    UUID a = productWithStock(stock, 1_000);
    UUID b = productWithStock(stock, 1_000);

    for (int i = 0; i < 200; i++) {
      List<ReservationDecision> pair =
          race(
              2,
              n ->
                  () ->
                      reservations.reserve(
                          UUID.randomUUID(),
                          n == 0
                              ? List.of(new ReservationLine(a, 1), new ReservationLine(b, 1))
                              : List.of(new ReservationLine(b, 1), new ReservationLine(a, 1))));
      assertThat(pair).allSatisfy(d -> assertThat(d.status()).isEqualTo(Status.RESERVED));
    }
    assertThat(stockOf(stock, a).reserved()).isEqualTo(400);
    assertThat(stockOf(stock, b).reserved()).isEqualTo(400);
  }

  /** The same order delivered ten times at once (at-least-once delivery) reserves once. */
  @Test
  void concurrentDuplicatesOfOneOrderReserveOnce() throws Exception {
    UUID product = productWithStock(stock, 10);
    UUID orderId = UUID.randomUUID();

    List<ReservationDecision> decisions =
        race(
            10, i -> () -> reservations.reserve(orderId, List.of(new ReservationLine(product, 3))));

    assertThat(decisions).allSatisfy(d -> assertThat(d.status()).isEqualTo(Status.RESERVED));
    assertThat(decisions).filteredOn(d -> !d.replay()).hasSize(1);
    assertThat(stockOf(stock, product).reserved()).isEqualTo(3);
  }

  private interface Task {
    Callable<ReservationDecision> make(int index);
  }

  /** Starts n tasks on virtual threads, releases them at the same instant, and collects results. */
  private static List<ReservationDecision> race(int n, Task task) throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<ReservationDecision>> futures = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        Callable<ReservationDecision> work = task.make(i);
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return work.call();
                }));
      }
      start.countDown();
      List<ReservationDecision> results = new ArrayList<>();
      for (Future<ReservationDecision> f : futures) {
        results.add(f.get());
      }
      return results;
    }
  }
}
