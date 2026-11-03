package dev.commerceflow.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.util.backoff.BackOffExecution;

class BackoffTest {

  static final Duration INITIAL = Duration.ofMillis(200);
  static final Duration MAX = Duration.ofSeconds(10);

  @Test
  void theCeilingDoublesUntilItReachesTheCap() {
    assertThat(Backoff.ceiling(0, INITIAL, MAX)).isEqualTo(200);
    assertThat(Backoff.ceiling(1, INITIAL, MAX)).isEqualTo(400);
    assertThat(Backoff.ceiling(5, INITIAL, MAX)).isEqualTo(6400);
    assertThat(Backoff.ceiling(6, INITIAL, MAX)).isEqualTo(10_000);
    // No overflow however long an outage lasts.
    assertThat(Backoff.ceiling(10_000, INITIAL, MAX)).isEqualTo(10_000);
  }

  @Test
  void fullJitterSpansZeroToTheCeiling() {
    RandomGenerator random = new java.util.SplittableRandom(42);
    Set<Long> buckets = new HashSet<>();
    for (int i = 0; i < 2000; i++) {
      long wait = Backoff.fullJitter(3, INITIAL, MAX, random).toMillis();
      assertThat(wait).isBetween(0L, 1600L);
      buckets.add(wait / 400);
    }
    // Spread over the whole range, not clustered at the top as "ceiling plus a little" would be.
    assertThat(buckets).contains(0L, 1L, 2L, 3L);
  }

  @Test
  void boundedRetriesStopAfterTheirCount() {
    BackOffExecution execution = Backoff.retries(INITIAL, MAX, 3).start();
    for (int i = 0; i < 3; i++) {
      assertThat(execution.nextBackOff()).isBetween(0L, MAX.toMillis());
    }
    assertThat(execution.nextBackOff()).isEqualTo(BackOffExecution.STOP);
  }

  @Test
  void unboundedRetriesNeverStopAndStayUnderTheCap() {
    BackOffExecution execution = Backoff.retries(INITIAL, MAX, -1).start();
    for (int i = 0; i < 500; i++) {
      assertThat(execution.nextBackOff()).isBetween(0L, MAX.toMillis());
    }
  }

  @Test
  void anUnreachableDatabaseIsAnOutageWhereverItIsInTheChain() {
    var noConnection =
        new CannotCreateTransactionException(
            "no connection", new SQLTransientConnectionException("pool timed out"));
    assertThat(Outages.isOutage(new ListenerExecutionFailedException("x", noConnection))).isTrue();
    assertThat(Outages.isOutage(new DataAccessResourceFailureException("down"))).isTrue();
  }

  /**
   * Seen in the failure-recovery tests: the statement fails on a connection Toxiproxy cut, then the
   * rollback fails on it too, and its exception is what surfaces.
   */
  @Test
  void aFailedRollbackOnADeadConnectionIsAnOutage() {
    var rollback =
        new TransactionSystemException(
            "JDBC rollback failed", new SQLException("An I/O error occurred", "08006"));
    assertThat(Outages.isOutage(new ListenerExecutionFailedException("x", rollback))).isTrue();

    var hidden = new TransactionSystemException("JDBC rollback failed");
    hidden.initApplicationException(new DataAccessResourceFailureException("statement failed"));
    assertThat(Outages.isOutage(hidden)).isTrue();

    // A rollback that failed for some other reason is not an outage.
    assertThat(
            Outages.isOutage(
                new TransactionSystemException(
                    "JDBC rollback failed", new SQLException("bug", "XX000"))))
        .isFalse();
  }

  @Test
  void aProcessingFailureIsNotAnOutage() {
    assertThat(
            Outages.isOutage(
                new ListenerExecutionFailedException(
                    "x", new DataIntegrityViolationException("check violated"))))
        .isFalse();
    assertThat(Outages.isOutage(new InboundEvents.InjectedFailure("c"))).isFalse();
    assertThat(Outages.isOutage(new IllegalStateException("bug"))).isFalse();
  }
}
