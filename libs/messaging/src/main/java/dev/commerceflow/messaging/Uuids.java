package dev.commerceflow.messaging;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * UUID version 7 (RFC 9562): a 48-bit millisecond timestamp followed by random bits. Event ids are
 * primary keys in outbox and inbox tables; time-ordered ids keep those B-tree indexes compact,
 * where random v4 ids would scatter inserts across the whole index.
 */
public final class Uuids {

  private static final SecureRandom RANDOM = new SecureRandom();

  private Uuids() {}

  public static UUID v7() {
    long millis = System.currentTimeMillis();
    long randA = RANDOM.nextInt(1 << 12);
    long msb = (millis << 16) | (0x7L << 12) | randA;
    long lsb = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
    return new UUID(msb, lsb);
  }
}
