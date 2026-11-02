package dev.commerceflow.order.saga;

import static org.assertj.core.api.Assertions.assertThat;

import dev.commerceflow.order.saga.SagaTransitions.Command;
import dev.commerceflow.order.saga.SagaTransitions.Key;
import dev.commerceflow.order.saga.SagaTransitions.Transition;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The transition table against ARCHITECTURE.md section E.2, one (state, event) pair at a time, plus
 * the properties the whole graph must have whatever the individual entries say.
 */
class SagaTransitionsTest {

  /** E.2, written out independently of the production table. */
  static final Map<Key, SagaState> DESIGN = new HashMap<>();

  static {
    design(
        SagaState.AWAITING_RESERVATION, SagaEvent.INVENTORY_RESERVED, SagaState.AWAITING_PAYMENT);
    design(SagaState.AWAITING_RESERVATION, SagaEvent.INVENTORY_REJECTED, SagaState.FAILED);
    design(
        SagaState.AWAITING_RESERVATION, SagaEvent.DEADLINE_EXCEEDED, SagaState.RELEASING_INVENTORY);
    design(SagaState.AWAITING_PAYMENT, SagaEvent.PAYMENT_SUCCEEDED, SagaState.AWAITING_COMMIT);
    design(SagaState.AWAITING_PAYMENT, SagaEvent.PAYMENT_DECLINED, SagaState.RELEASING_INVENTORY);
    design(SagaState.AWAITING_PAYMENT, SagaEvent.PAYMENT_TIMED_OUT, SagaState.RELEASING_INVENTORY);
    design(
        SagaState.AWAITING_PAYMENT,
        SagaEvent.PAYMENT_PENDING_RECONCILIATION,
        SagaState.AWAITING_RECONCILIATION);
    design(SagaState.AWAITING_PAYMENT, SagaEvent.DEADLINE_EXCEEDED, SagaState.CANCELLING_PAYMENT);
    design(
        SagaState.AWAITING_RECONCILIATION, SagaEvent.PAYMENT_SUCCEEDED, SagaState.AWAITING_COMMIT);
    design(
        SagaState.AWAITING_RECONCILIATION,
        SagaEvent.PAYMENT_DECLINED,
        SagaState.RELEASING_INVENTORY);
    design(
        SagaState.AWAITING_RECONCILIATION,
        SagaEvent.PAYMENT_TIMED_OUT,
        SagaState.RELEASING_INVENTORY);
    design(
        SagaState.CANCELLING_PAYMENT, SagaEvent.PAYMENT_CANCELLED, SagaState.RELEASING_INVENTORY);
    design(SagaState.CANCELLING_PAYMENT, SagaEvent.PAYMENT_REFUNDED, SagaState.RELEASING_INVENTORY);
    design(SagaState.CANCELLING_PAYMENT, SagaEvent.PAYMENT_DECLINED, SagaState.RELEASING_INVENTORY);
    design(
        SagaState.CANCELLING_PAYMENT, SagaEvent.PAYMENT_TIMED_OUT, SagaState.RELEASING_INVENTORY);
    design(SagaState.AWAITING_COMMIT, SagaEvent.INVENTORY_COMMITTED, SagaState.COMPLETED);
    design(
        SagaState.AWAITING_COMMIT, SagaEvent.INVENTORY_COMMIT_FAILED, SagaState.REFUNDING_PAYMENT);
    design(SagaState.REFUNDING_PAYMENT, SagaEvent.PAYMENT_REFUNDED, SagaState.RELEASING_INVENTORY);
    design(SagaState.RELEASING_INVENTORY, SagaEvent.INVENTORY_RELEASED, SagaState.FAILED);
  }

  static void design(SagaState from, SagaEvent event, SagaState to) {
    DESIGN.put(new Key(from, event), to);
  }

  static Stream<Arguments> everyPair() {
    return EnumSet.allOf(SagaState.class).stream()
        .flatMap(s -> EnumSet.allOf(SagaEvent.class).stream().map(e -> Arguments.of(s, e)));
  }

  @ParameterizedTest(name = "{0} + {1}")
  @MethodSource("everyPair")
  void everyStateAndEventPairDoesWhatTheDesignSays(SagaState state, SagaEvent event) {
    SagaState expected = DESIGN.get(new Key(state, event));
    if (expected == null) {
      // Not valid here: acknowledged and ignored, never applied.
      assertThat(SagaTransitions.next(state, event)).isEmpty();
    } else {
      assertThat(SagaTransitions.next(state, event)).map(Transition::to).contains(expected);
    }
  }

  @Test
  void theTableHasNothingTheDesignDoesNot() {
    assertThat(SagaTransitions.TABLE.keySet()).isEqualTo(DESIGN.keySet());
  }

  @Test
  void finishedSagasIgnoreEverything() {
    for (SagaEvent event : SagaEvent.values()) {
      assertThat(SagaTransitions.next(SagaState.COMPLETED, event)).isEmpty();
      assertThat(SagaTransitions.next(SagaState.FAILED, event)).isEmpty();
    }
  }

  @Test
  void everyStateCanStillFinish() {
    for (SagaState state : SagaState.values()) {
      assertThat(canReachTerminal(state)).as("%s can reach COMPLETED or FAILED", state).isTrue();
    }
  }

  /**
   * INV-4 and INV-5 on the model: follow every path, tracking whether the customer's money is held.
   * No path may end FAILED holding it, and none may end COMPLETED without it.
   */
  @Test
  void noPathFailsWithTheMoneyTakenOrCompletesWithoutIt() {
    record Node(SagaState state, boolean paid) {}
    Set<Node> seen = new HashSet<>();
    Deque<Node> queue = new ArrayDeque<>();
    queue.add(new Node(SagaState.AWAITING_RESERVATION, false));
    while (!queue.isEmpty()) {
      Node node = queue.poll();
      if (!seen.add(node)) {
        continue;
      }
      SagaTransitions.TABLE.forEach(
          (key, transition) -> {
            if (key.state() == node.state()) {
              boolean paid =
                  switch (key.event()) {
                    case PAYMENT_SUCCEEDED -> true;
                    // Answers to CancelPayment: every one means nothing is held any more.
                    case PAYMENT_REFUNDED, PAYMENT_CANCELLED -> false;
                    default -> node.paid();
                  };
              queue.add(new Node(transition.to(), paid));
            }
          });
    }
    assertThat(seen).doesNotContain(new Node(SagaState.FAILED, true));
    assertThat(seen).doesNotContain(new Node(SagaState.COMPLETED, false));
    assertThat(seen)
        .contains(new Node(SagaState.FAILED, false), new Node(SagaState.COMPLETED, true));
  }

  /** INV-2/INV-5: an order fails only when it never got stock or its stock is back. */
  @Test
  void anOrderFailsOnlyWithNoStockHeld() {
    SagaTransitions.TABLE.forEach(
        (key, transition) -> {
          if (transition.to() == SagaState.FAILED) {
            assertThat(key.event())
                .isIn(SagaEvent.INVENTORY_REJECTED, SagaEvent.INVENTORY_RELEASED);
          }
          if (transition.to() == SagaState.COMPLETED) {
            assertThat(key.event()).isEqualTo(SagaEvent.INVENTORY_COMMITTED);
          }
        });
  }

  /** The order's status and the saga's state can never disagree about how far checkout got. */
  @Test
  void theOrderStatusFollowsTheSaga() {
    SagaTransitions.TABLE.forEach(
        (key, t) -> {
          String expected =
              switch (t.to()) {
                case AWAITING_PAYMENT -> "PAYMENT_PROCESSING";
                case COMPLETED -> "CONFIRMED";
                case FAILED -> "FAILED";
                default -> null;
              };
          assertThat(t.orderStatus()).as("%s -> %s", key, t.to()).isEqualTo(expected);
        });
  }

  /** Every reason the table uses is one the event contracts allow. */
  @Test
  void failureReasonsAndCommandReasonsAreTheContractsOwn() {
    Set<String> orderFailed = contractEnum("OrderFailed");
    Set<String> release = contractEnum("ReleaseInventory");
    Set<String> cancel = contractEnum("CancelPayment");
    SagaTransitions.TABLE.forEach(
        (key, t) -> {
          if (t.failureReason() != null) {
            assertThat(orderFailed).contains(t.failureReason());
          }
          switch (t.command()) {
            case RELEASE_INVENTORY -> assertThat(release).contains(t.commandReason());
            case CANCEL_PAYMENT -> assertThat(cancel).contains(t.commandReason());
            default -> assertThat(t.commandReason()).isNull();
          }
        });
  }

  /** OrderFailed needs a reason: every path into FAILED must have decided one on the way. */
  @Test
  void everyPathToFailedHasDecidedWhy() {
    record Node(SagaState state, boolean reasoned) {}
    Set<Node> seen = new HashSet<>();
    Deque<Node> queue = new ArrayDeque<>();
    queue.add(new Node(SagaState.AWAITING_RESERVATION, false));
    while (!queue.isEmpty()) {
      Node node = queue.poll();
      if (seen.add(node)) {
        SagaTransitions.TABLE.forEach(
            (key, t) -> {
              if (key.state() == node.state()) {
                queue.add(new Node(t.to(), node.reasoned() || t.failureReason() != null));
              }
            });
      }
    }
    assertThat(seen).doesNotContain(new Node(SagaState.FAILED, false));
  }

  @Test
  void eachCommandIsSentOnTheStepThatWaitsForItsAnswer() {
    SagaTransitions.TABLE
        .values()
        .forEach(
            t -> {
              Command expected =
                  switch (t.to()) {
                    case AWAITING_PAYMENT -> Command.AUTHORIZE_PAYMENT;
                    case AWAITING_COMMIT -> Command.COMMIT_INVENTORY;
                    case RELEASING_INVENTORY -> Command.RELEASE_INVENTORY;
                    case CANCELLING_PAYMENT, REFUNDING_PAYMENT -> Command.CANCEL_PAYMENT;
                    default -> Command.NONE;
                  };
              assertThat(t.command()).isEqualTo(expected);
            });
  }

  @Test
  void paymentFailedIsSplitByItsReason() {
    assertThat(SagaEvent.of("PaymentFailed", "DECLINED")).contains(SagaEvent.PAYMENT_DECLINED);
    assertThat(SagaEvent.of("PaymentFailed", "INSUFFICIENT_FUNDS"))
        .contains(SagaEvent.PAYMENT_DECLINED);
    assertThat(SagaEvent.of("PaymentFailed", "TIMEOUT_UNRESOLVED"))
        .contains(SagaEvent.PAYMENT_TIMED_OUT);
    assertThat(SagaEvent.of("PaymentFailed", "CANCELLED")).contains(SagaEvent.PAYMENT_CANCELLED);
    assertThat(SagaEvent.of("PaymentFailed", "SOMETHING_NEW")).isEmpty();
    assertThat(SagaEvent.of("OrderCreated", null)).isEmpty();
  }

  // --- Graph helpers -------------------------------------------------------------------------

  static boolean canReachTerminal(SagaState start) {
    Set<SagaState> seen = EnumSet.noneOf(SagaState.class);
    Deque<SagaState> queue = new ArrayDeque<>();
    queue.add(start);
    while (!queue.isEmpty()) {
      SagaState state = queue.poll();
      if (state.terminal()) {
        return true;
      }
      if (seen.add(state)) {
        SagaTransitions.TABLE.forEach(
            (key, t) -> {
              if (key.state() == state) {
                queue.add(t.to());
              }
            });
      }
    }
    return false;
  }

  /** The payload's reason enum in an event's contract, read from its schema file. */
  static Set<String> contractEnum(String eventType) {
    try (InputStream in =
        SagaTransitionsTest.class.getResourceAsStream("/events/v1/" + eventType + ".schema.json")) {
      JsonNode values =
          JsonMapper.builder()
              .build()
              .readTree(in)
              .path("properties")
              .path("payload")
              .path("properties")
              .path("reason")
              .path("enum");
      Set<String> result = new HashSet<>();
      values.forEach(v -> result.add(v.asString()));
      assertThat(result).as("%s reason enum", eventType).isNotEmpty();
      return result;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
