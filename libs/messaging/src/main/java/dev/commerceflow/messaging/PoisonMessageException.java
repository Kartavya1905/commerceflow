package dev.commerceflow.messaging;

/**
 * A message that can never be processed, however often it is retried: not JSON, no envelope, or a
 * version this consumer does not understand. It goes straight to the dead-letter topic instead of
 * blocking its partition through every retry.
 */
public class PoisonMessageException extends RuntimeException {

  public PoisonMessageException(String message) {
    super(message);
  }

  public PoisonMessageException(String message, Throwable cause) {
    super(message, cause);
  }
}
