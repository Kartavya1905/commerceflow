package dev.commerceflow.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.context.request.ServletWebRequest;

class ProblemDetailsAdviceTest {

  final ProblemDetailsAdvice advice = new ProblemDetailsAdvice();
  final ServletWebRequest request = new ServletWebRequest(new MockHttpServletRequest());

  /** ARCHITECTURE.md E.4: with the database gone, clients get 503 and a hint when to retry. */
  @Test
  void anUnreachableDatabaseIs503WithRetryAfter() {
    for (RuntimeException e :
        new RuntimeException[] {
          new CannotCreateTransactionException(
              "no connection", new SQLTransientConnectionException("pool timed out")),
          new DataAccessResourceFailureException("connection refused")
        }) {
      ResponseEntity<Object> response = advice.handleUnavailable(e, request);
      assertThat(response.getStatusCode().value()).isEqualTo(503);
      assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
      ProblemDetail problem = (ProblemDetail) response.getBody();
      assertThat(problem.getType().toString()).endsWith("unavailable");
      // Nothing about the cause reaches the client.
      assertThat(problem.getDetail()).doesNotContain("pool", "refused");
    }
  }

  /**
   * Found by the failure-recovery tests: a connection cut mid-request failed the statement, then
   * the rollback, and the rollback's exception surfaced as a 500.
   */
  @Test
  void aFailedRollbackOnADeadConnectionIsStill503() {
    var rollback =
        new TransactionSystemException(
            "JDBC rollback failed", new SQLException("An I/O error occurred", "08006"));
    assertThat(advice.handleTransactionFailure(rollback, request).getStatusCode().value())
        .isEqualTo(503);

    var hidden = new TransactionSystemException("JDBC rollback failed");
    hidden.initApplicationException(new DataAccessResourceFailureException("statement failed"));
    assertThat(advice.handleTransactionFailure(hidden, request).getStatusCode().value())
        .isEqualTo(503);

    var other =
        new TransactionSystemException("JDBC commit failed", new SQLException("bug", "XX000"));
    assertThat(advice.handleTransactionFailure(other, request).getStatusCode().value())
        .isEqualTo(500);
  }
}
