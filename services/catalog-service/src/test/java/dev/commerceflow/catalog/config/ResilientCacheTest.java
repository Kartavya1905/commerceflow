package dev.commerceflow.catalog.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.transaction.TransactionAwareCacheDecorator;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Reproduces the after-commit path directly: a transaction-aware cache defers its writes to an
 * after-commit callback, where Spring's CacheErrorHandler does not apply.
 */
class ResilientCacheTest {

  @AfterEach
  void clearSynchronization() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void withoutTheWrapperAFailingCacheBreaksTheCommitCallback() {
    Cache transactional = new TransactionAwareCacheDecorator(new BrokenCache());
    TransactionSynchronizationManager.initSynchronization();
    transactional.evict("key");

    // This is what would turn a committed admin edit into a 500 when Redis is down.
    assertThat(runAfterCommitCallbacks()).isEqualTo(1);
  }

  @Test
  void withTheWrapperFailedDeferredWritesAreSkipped() {
    BrokenCache broken = new BrokenCache();
    Cache transactional = new TransactionAwareCacheDecorator(new ResilientCache(broken));
    TransactionSynchronizationManager.initSynchronization();
    transactional.put("key", "value");
    transactional.evict("key");
    transactional.clear();

    assertThat(runAfterCommitCallbacks()).isZero();
    assertThat(broken.attempts.get()).as("each deferred write was still attempted").isEqualTo(3);
  }

  @Test
  void healthyCachesBehaveNormally() {
    ResilientCache cache = new ResilientCache(new ConcurrentMapCache("product"));
    cache.put("a", "1");
    assertThat(cache.get("a", String.class)).isEqualTo("1");
    cache.evict("a");
    assertThatCode(() -> cache.get("a")).doesNotThrowAnyException();
    assertThat(cache.get("a")).isNull();
  }

  /** Runs every registered afterCommit callback and returns how many threw. */
  private static int runAfterCommitCallbacks() {
    List<TransactionSynchronization> callbacks =
        TransactionSynchronizationManager.getSynchronizations();
    int failures = 0;
    for (TransactionSynchronization callback : callbacks) {
      try {
        callback.afterCommit();
      } catch (RuntimeException e) {
        failures++;
      }
    }
    return failures;
  }

  /** Every write fails, as a cache whose server is unreachable would. */
  private static final class BrokenCache extends ConcurrentMapCache {
    final AtomicInteger attempts = new AtomicInteger();

    BrokenCache() {
      super("broken");
    }

    @Override
    public void put(Object key, Object value) {
      attempts.incrementAndGet();
      throw new IllegalStateException("cache server unreachable");
    }

    @Override
    public void evict(Object key) {
      attempts.incrementAndGet();
      throw new IllegalStateException("cache server unreachable");
    }

    @Override
    public void clear() {
      attempts.incrementAndGet();
      throw new IllegalStateException("cache server unreachable");
    }
  }
}
