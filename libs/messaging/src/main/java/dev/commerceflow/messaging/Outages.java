package dev.commerceflow.messaging;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

/**
 * Tells "the database is unreachable" apart from "this message cannot be processed". The first is
 * fixed by waiting, so it is retried without limit; the second by a person, so it goes to the
 * dead-letter topic after a few tries.
 *
 * <p>A connection that breaks mid-transaction fails twice: the statement, then the rollback on the
 * same dead connection, whose TransactionSystemException hides the first. Both its cause and the
 * original exception it carries are therefore looked at, as is any SQLException in the connection
 * class (SQLState 08). web-common's ProblemDetailsAdvice applies the same rule to HTTP requests.
 */
final class Outages {

  private Outages() {}

  static boolean isOutage(Throwable failure) {
    Deque<Throwable> pending = new ArrayDeque<>();
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    pending.push(failure);
    while (!pending.isEmpty()) {
      Throwable t = pending.pop();
      if (!seen.add(t)) {
        continue;
      }
      if (t instanceof DataAccessResourceFailureException
          || t instanceof CannotCreateTransactionException
          || t instanceof SQLTransientConnectionException
          || (t instanceof SQLException sql
              && sql.getSQLState() != null
              && sql.getSQLState().startsWith("08"))) {
        return true;
      }
      if (t instanceof TransactionSystemException tx && tx.getOriginalException() != null) {
        pending.push(tx.getOriginalException());
      }
      if (t.getCause() != null) {
        pending.push(t.getCause());
      }
    }
    return false;
  }
}
