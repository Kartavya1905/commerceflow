package dev.commerceflow.catalog.config;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

/**
 * Makes cache writes best-effort. Failed puts, evictions and clears are logged and skipped.
 *
 * <p>Spring's {@code CacheErrorHandler} covers cache calls made during the request, but a
 * transaction-aware cache runs puts and evictions later, in an after-commit callback that the error
 * handler never sees, and Spring's decorator does not catch there (ResilientCacheTest shows this).
 * Spring Data Redis 4.1 happens not to throw on writes to an unreachable server (RedisDownIT pins
 * that down), so today this wrapper is a guarantee rather than a fix: whatever the cache library
 * does, a cache failure after commit can never turn a committed write into a 500. Reads are left to
 * the {@code CacheErrorHandler}.
 */
final class ResilientCache implements Cache {

  private static final Logger log = LoggerFactory.getLogger("dev.commerceflow.catalog.cache");

  private final Cache target;

  ResilientCache(Cache target) {
    this.target = target;
  }

  @Override
  public String getName() {
    return target.getName();
  }

  @Override
  public Object getNativeCache() {
    return target.getNativeCache();
  }

  @Override
  public ValueWrapper get(Object key) {
    return target.get(key);
  }

  @Override
  public <T> T get(Object key, Class<T> type) {
    return target.get(key, type);
  }

  @Override
  public <T> T get(Object key, Callable<T> valueLoader) {
    return target.get(key, valueLoader);
  }

  @Override
  public void put(Object key, Object value) {
    guard("put", key, () -> target.put(key, value));
  }

  @Override
  public void evict(Object key) {
    guard("evict", key, () -> target.evict(key));
  }

  @Override
  public void clear() {
    guard("clear", "*", target::clear);
  }

  private void guard(String operation, Object key, Runnable action) {
    try {
      action.run();
    } catch (RuntimeException e) {
      log.warn(
          "Cache '{}' {} failed for key '{}'; continuing without the cache: {}",
          getName(),
          operation,
          key,
          e.toString());
    }
  }

  /** Wraps every cache of a delegate manager, once per cache name. */
  static final class Manager implements CacheManager {

    private final CacheManager target;
    private final ConcurrentMap<String, Cache> wrapped = new ConcurrentHashMap<>();

    Manager(CacheManager target) {
      this.target = target;
    }

    @Override
    public Cache getCache(String name) {
      Cache cache = target.getCache(name);
      return cache == null ? null : wrapped.computeIfAbsent(name, n -> new ResilientCache(cache));
    }

    @Override
    public Collection<String> getCacheNames() {
      return List.copyOf(target.getCacheNames());
    }
  }
}
