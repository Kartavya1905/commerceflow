package dev.commerceflow.inventory;

import static dev.commerceflow.inventory.InventoryFixtures.assertInvariantsHold;
import static dev.commerceflow.inventory.InventoryFixtures.productWithStock;
import static dev.commerceflow.inventory.InventoryFixtures.stockOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.commerceflow.inventory.reservation.ReservationDecision;
import dev.commerceflow.inventory.reservation.ReservationDecision.RejectReason;
import dev.commerceflow.inventory.reservation.ReservationDecision.Status;
import dev.commerceflow.inventory.reservation.ReservationLine;
import dev.commerceflow.inventory.reservation.ReservationService;
import dev.commerceflow.inventory.reservation.ReservationService.CommitFailure;
import dev.commerceflow.inventory.reservation.ReservationService.ReleaseResult;
import dev.commerceflow.inventory.stock.StockService;
import dev.commerceflow.inventory.stock.StockService.Reason;
import dev.commerceflow.inventory.stock.StockService.Stock;
import dev.commerceflow.testsupport.Containers;
import dev.commerceflow.testsupport.TestTokens;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Every transition of a reservation, including the out-of-order and repeated ones. */
@SpringBootTest
@Import({Containers.Postgres.class, Containers.Kafka.class, TestTokens.class})
class ReservationLifecycleIT {

  @Autowired ReservationService reservations;
  @Autowired StockService stock;
  @Autowired JdbcClient jdbc;

  @AfterEach
  void invariantsHold() {
    assertInvariantsHold(jdbc);
  }

  @Test
  void reserveThenCommitMovesStockToCommittedOnce() {
    UUID product = productWithStock(stock, 5);
    UUID order = UUID.randomUUID();
    reservations.reserve(order, List.of(new ReservationLine(product, 2)));

    assertThat(reservations.commit(order).committed()).isTrue();
    assertThat(reservations.commit(order).replay()).as("second commit is a replay").isTrue();

    Stock after = stockOf(stock, product);
    assertThat(after.available()).isEqualTo(3);
    assertThat(after.reserved()).isZero();
    assertThat(after.committed()).isEqualTo(2);
    assertThat(reservations.release(order)).isEqualTo(ReleaseResult.NOT_RELEASABLE);
    assertThat(stockOf(stock, product).committed()).isEqualTo(2);
  }

  @Test
  void releasingTwiceReturnsStockOnce() {
    UUID product = productWithStock(stock, 5);
    UUID order = UUID.randomUUID();
    reservations.reserve(order, List.of(new ReservationLine(product, 4)));

    assertThat(reservations.release(order)).isEqualTo(ReleaseResult.RELEASED);
    assertThat(reservations.release(order)).isEqualTo(ReleaseResult.ALREADY_RELEASED);
    assertThat(stockOf(stock, product).available()).isEqualTo(5);
  }

  /** The saga gave up and compensated before the reserve request arrived. */
  @Test
  void aReleaseBeforeTheReserveLeavesATombstoneThatBlocksIt() {
    UUID product = productWithStock(stock, 5);
    UUID order = UUID.randomUUID();

    assertThat(reservations.release(order)).isEqualTo(ReleaseResult.TOMBSTONED);
    ReservationDecision late =
        reservations.reserve(order, List.of(new ReservationLine(product, 1)));

    assertThat(late.replay()).isTrue();
    assertThat(late.status()).isEqualTo(Status.RELEASED);
    assertThat(stockOf(stock, product).available()).isEqualTo(5);
  }

  @Test
  void aRejectionIsFinalEvenIfStockArrivesLater() {
    UUID product = productWithStock(stock, 1);
    UUID order = UUID.randomUUID();
    assertThat(reservations.reserve(order, List.of(new ReservationLine(product, 2))).status())
        .isEqualTo(Status.REJECTED);

    stock.adjust(product, 10, Reason.RESTOCK, null, TestTokens.ADMIN_ID);
    ReservationDecision retry =
        reservations.reserve(order, List.of(new ReservationLine(product, 2)));

    assertThat(retry.replay()).isTrue();
    assertThat(retry.status()).isEqualTo(Status.REJECTED);
    assertThat(stockOf(stock, product).reserved()).isZero();
  }

  @Test
  void oneShortLineRejectsTheWholeOrderAndTouchesNothing() {
    UUID plenty = productWithStock(stock, 10);
    UUID scarce = productWithStock(stock, 1);

    ReservationDecision decision =
        reservations.reserve(
            UUID.randomUUID(),
            List.of(new ReservationLine(plenty, 3), new ReservationLine(scarce, 2)));

    assertThat(decision.status()).isEqualTo(Status.REJECTED);
    assertThat(decision.rejectReason()).isEqualTo(RejectReason.OUT_OF_STOCK);
    assertThat(decision.rejectedProductIds()).containsExactly(scarce);
    assertThat(stockOf(stock, plenty).available()).isEqualTo(10);
  }

  @Test
  void unknownProductsAreNamedInTheRejection() {
    UUID known = productWithStock(stock, 10);
    UUID unknown = UUID.randomUUID();

    ReservationDecision decision =
        reservations.reserve(
            UUID.randomUUID(),
            List.of(new ReservationLine(known, 1), new ReservationLine(unknown, 1)));

    assertThat(decision.rejectReason()).isEqualTo(RejectReason.UNKNOWN_PRODUCT);
    assertThat(decision.rejectedProductIds()).containsExactly(unknown);
    // Survives a round trip through the stored decision.
    assertThat(reservations.find(decision.orderId()).orElseThrow().rejectedProductIds())
        .containsExactly(unknown);
  }

  @Test
  void commitFailuresUseTheContractReasons() {
    assertThat(reservations.commit(UUID.randomUUID()).failure())
        .isEqualTo(CommitFailure.RESERVATION_NOT_FOUND);

    UUID product = productWithStock(stock, 5);
    UUID order = UUID.randomUUID();
    reservations.reserve(order, List.of(new ReservationLine(product, 1)));
    reservations.release(order);
    assertThat(reservations.commit(order).failure()).isEqualTo(CommitFailure.RESERVATION_EXPIRED);
  }

  @Test
  void theLongStopExpiresForgottenReservationsAndReturnsTheirStock() {
    UUID product = productWithStock(stock, 5);
    UUID order = UUID.randomUUID();
    reservations.reserve(order, List.of(new ReservationLine(product, 2)));
    jdbc.sql(
            "update order_reservations set expires_at = now() - interval '1 minute' where order_id = ?")
        .param(order)
        .update();

    assertThat(reservations.overdue(100)).contains(order);
    assertThat(reservations.expireOne(order)).isTrue();
    assertThat(reservations.expireOne(order)).as("only once").isFalse();
    assertThat(reservations.find(order).orElseThrow().status()).isEqualTo(Status.EXPIRED);
    assertThat(stockOf(stock, product).available()).isEqualTo(5);
  }

  @Test
  void duplicateProductLinesAreAProgrammingError() {
    UUID product = productWithStock(stock, 5);
    assertThatThrownBy(
            () ->
                reservations.reserve(
                    UUID.randomUUID(),
                    List.of(new ReservationLine(product, 1), new ReservationLine(product, 1))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** INV-2 is enforced by the database itself, not only by this code. */
  @Test
  void theDatabaseRejectsAnyWriteThatCreatesStockFromNothing() {
    UUID product = productWithStock(stock, 5);
    assertThatThrownBy(
            () ->
                jdbc.sql("update inventory set available = available + 1 where product_id = ?")
                    .param(product)
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
