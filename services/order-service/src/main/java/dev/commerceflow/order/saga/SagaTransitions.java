package dev.commerceflow.order.saga;

import static dev.commerceflow.order.saga.SagaEvent.DEADLINE_EXCEEDED;
import static dev.commerceflow.order.saga.SagaEvent.INVENTORY_COMMITTED;
import static dev.commerceflow.order.saga.SagaEvent.INVENTORY_COMMIT_FAILED;
import static dev.commerceflow.order.saga.SagaEvent.INVENTORY_REJECTED;
import static dev.commerceflow.order.saga.SagaEvent.INVENTORY_RELEASED;
import static dev.commerceflow.order.saga.SagaEvent.INVENTORY_RESERVED;
import static dev.commerceflow.order.saga.SagaEvent.PAYMENT_CANCELLED;
import static dev.commerceflow.order.saga.SagaEvent.PAYMENT_DECLINED;
import static dev.commerceflow.order.saga.SagaEvent.PAYMENT_PENDING_RECONCILIATION;
import static dev.commerceflow.order.saga.SagaEvent.PAYMENT_REFUNDED;
import static dev.commerceflow.order.saga.SagaEvent.PAYMENT_SUCCEEDED;
import static dev.commerceflow.order.saga.SagaEvent.PAYMENT_TIMED_OUT;
import static dev.commerceflow.order.saga.SagaState.AWAITING_COMMIT;
import static dev.commerceflow.order.saga.SagaState.AWAITING_PAYMENT;
import static dev.commerceflow.order.saga.SagaState.AWAITING_RECONCILIATION;
import static dev.commerceflow.order.saga.SagaState.AWAITING_RESERVATION;
import static dev.commerceflow.order.saga.SagaState.CANCELLING_PAYMENT;
import static dev.commerceflow.order.saga.SagaState.COMPLETED;
import static dev.commerceflow.order.saga.SagaState.FAILED;
import static dev.commerceflow.order.saga.SagaState.REFUNDING_PAYMENT;
import static dev.commerceflow.order.saga.SagaState.RELEASING_INVENTORY;

import java.util.Map;
import java.util.Optional;

/**
 * The whole saga as data: for each (state, event) that means something, where it goes and what it
 * does. A pair that is not here is not valid in that state, and is acknowledged and ignored. This
 * is the single place to read to know what the checkout does; SagaTransitionsTest checks it against
 * the design, pair by pair.
 */
public final class SagaTransitions {

  /** The message sent on a transition. */
  public enum Command {
    NONE,
    AUTHORIZE_PAYMENT,
    COMMIT_INVENTORY,
    RELEASE_INVENTORY,
    CANCEL_PAYMENT
  }

  /**
   * @param orderStatus the customer-visible status to move the order to, or null to leave it
   * @param commandReason the reason field of ReleaseInventory or CancelPayment
   * @param failureReason what the order will fail with; null keeps any already decided
   */
  public record Transition(
      SagaState to,
      String orderStatus,
      Command command,
      String commandReason,
      String failureReason) {}

  public record Key(SagaState state, SagaEvent event) {}

  private static final String PAYMENT_PROCESSING = "PAYMENT_PROCESSING";

  static final Map<Key, Transition> TABLE =
      Map.ofEntries(
          // Waiting for inventory to reserve the stock.
          on(AWAITING_RESERVATION, INVENTORY_RESERVED)
              .go(AWAITING_PAYMENT, PAYMENT_PROCESSING, Command.AUTHORIZE_PAYMENT, null, null),
          on(AWAITING_RESERVATION, INVENTORY_REJECTED)
              .go(FAILED, "FAILED", Command.NONE, null, "OUT_OF_STOCK"),
          // Nothing heard: release, in case a reservation was made and its answer lost.
          on(AWAITING_RESERVATION, DEADLINE_EXCEEDED)
              .go(
                  RELEASING_INVENTORY,
                  null,
                  Command.RELEASE_INVENTORY,
                  "RESERVATION_TIMEOUT",
                  "INTERNAL_ERROR"),

          // Waiting for the payment's outcome.
          on(AWAITING_PAYMENT, PAYMENT_SUCCEEDED)
              .go(AWAITING_COMMIT, null, Command.COMMIT_INVENTORY, null, null),
          on(AWAITING_PAYMENT, PAYMENT_DECLINED)
              .go(
                  RELEASING_INVENTORY,
                  null,
                  Command.RELEASE_INVENTORY,
                  "PAYMENT_FAILED",
                  "PAYMENT_DECLINED"),
          on(AWAITING_PAYMENT, PAYMENT_TIMED_OUT)
              .go(
                  RELEASING_INVENTORY,
                  null,
                  Command.RELEASE_INVENTORY,
                  "PAYMENT_TIMEOUT",
                  "PAYMENT_TIMEOUT"),
          // Unknown is not failed: the charge may have happened. Wait for reconciliation.
          on(AWAITING_PAYMENT, PAYMENT_PENDING_RECONCILIATION)
              .go(AWAITING_RECONCILIATION, null, Command.NONE, null, null),
          // Nothing heard: cancel, which voids an attempt or refunds one that went through.
          on(AWAITING_PAYMENT, DEADLINE_EXCEEDED)
              .go(
                  CANCELLING_PAYMENT,
                  null,
                  Command.CANCEL_PAYMENT,
                  "SAGA_TIMEOUT",
                  "PAYMENT_TIMEOUT"),

          // Waiting for payment-service to find out what happened to the charge.
          on(AWAITING_RECONCILIATION, PAYMENT_SUCCEEDED)
              .go(AWAITING_COMMIT, null, Command.COMMIT_INVENTORY, null, null),
          on(AWAITING_RECONCILIATION, PAYMENT_DECLINED)
              .go(
                  RELEASING_INVENTORY,
                  null,
                  Command.RELEASE_INVENTORY,
                  "PAYMENT_FAILED",
                  "PAYMENT_DECLINED"),
          on(AWAITING_RECONCILIATION, PAYMENT_TIMED_OUT)
              .go(
                  RELEASING_INVENTORY,
                  null,
                  Command.RELEASE_INVENTORY,
                  "PAYMENT_TIMEOUT",
                  "PAYMENT_TIMEOUT"),

          // Waiting for the cancel to settle. Every answer means no money is held any more.
          on(CANCELLING_PAYMENT, PAYMENT_CANCELLED)
              .go(RELEASING_INVENTORY, null, Command.RELEASE_INVENTORY, "PAYMENT_TIMEOUT", null),
          on(CANCELLING_PAYMENT, PAYMENT_REFUNDED)
              .go(RELEASING_INVENTORY, null, Command.RELEASE_INVENTORY, "PAYMENT_TIMEOUT", null),
          on(CANCELLING_PAYMENT, PAYMENT_DECLINED)
              .go(RELEASING_INVENTORY, null, Command.RELEASE_INVENTORY, "PAYMENT_TIMEOUT", null),
          on(CANCELLING_PAYMENT, PAYMENT_TIMED_OUT)
              .go(RELEASING_INVENTORY, null, Command.RELEASE_INVENTORY, "PAYMENT_TIMEOUT", null),

          // Paid; waiting for the reserved stock to be committed.
          on(AWAITING_COMMIT, INVENTORY_COMMITTED)
              .go(COMPLETED, "CONFIRMED", Command.NONE, null, null),
          on(AWAITING_COMMIT, INVENTORY_COMMIT_FAILED)
              .go(
                  REFUNDING_PAYMENT,
                  null,
                  Command.CANCEL_PAYMENT,
                  "COMMIT_FAILED",
                  "INTERNAL_ERROR"),
          on(REFUNDING_PAYMENT, PAYMENT_REFUNDED)
              .go(RELEASING_INVENTORY, null, Command.RELEASE_INVENTORY, "COMMIT_FAILED", null),

          // The order fails only once its stock is back.
          on(RELEASING_INVENTORY, INVENTORY_RELEASED)
              .go(FAILED, "FAILED", Command.NONE, null, null));

  private SagaTransitions() {}

  public static Optional<Transition> next(SagaState state, SagaEvent event) {
    return Optional.ofNullable(TABLE.get(new Key(state, event)));
  }

  private static KeyBuilder on(SagaState state, SagaEvent event) {
    return new KeyBuilder(new Key(state, event));
  }

  private record KeyBuilder(Key key) {
    Map.Entry<Key, Transition> go(
        SagaState to, String orderStatus, Command command, String commandReason, String failure) {
      return Map.entry(key, new Transition(to, orderStatus, command, commandReason, failure));
    }
  }
}
