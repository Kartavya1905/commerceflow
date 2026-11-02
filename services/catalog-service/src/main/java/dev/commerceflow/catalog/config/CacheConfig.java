package dev.commerceflow.catalog.config;

import dev.commerceflow.catalog.product.ProductPage;
import dev.commerceflow.catalog.product.ProductView;
import org.apache.commons.logging.LogFactory;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.LoggingCacheErrorHandler;
import org.springframework.cache.transaction.TransactionAwareCacheManagerProxy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.BatchStrategies;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

/**
 * Cache-aside in Redis for public reads.
 *
 * <ul>
 *   <li>Transaction-aware: puts and evictions happen after the database commit, so a rolled-back
 *       edit never clears the cache and readers cannot re-cache the old value mid-transaction.
 *   <li>Typed JSON serializers per cache, so no class names are stored in Redis and nothing is
 *       deserialised polymorphically.
 *   <li>Clearing a cache uses SCAN, not KEYS, which would block Redis on a large keyspace.
 *   <li>Cache errors are logged and skipped: a Redis outage slows reads, it does not fail them.
 *       Read errors go through the {@link CacheErrorHandler}; deferred writes through {@link
 *       ResilientCache}.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching
public class CacheConfig implements CachingConfigurer {

  public static final String PRODUCT = "product";
  public static final String PRODUCT_PAGES = "product-pages";

  @Bean
  CacheManager cacheManager(RedisConnectionFactory connections, CatalogProperties properties) {
    RedisCacheConfiguration defaults =
        RedisCacheConfiguration.defaultCacheConfig()
            .prefixCacheNameWith("catalog:")
            .disableCachingNullValues();
    RedisCacheManager redis =
        RedisCacheManager.builder(
                RedisCacheWriter.nonLockingRedisCacheWriter(connections, BatchStrategies.scan(500)))
            .cacheDefaults(defaults)
            .withCacheConfiguration(
                PRODUCT,
                defaults
                    .entryTtl(properties.productCacheTtl())
                    .serializeValuesWith(
                        SerializationPair.fromSerializer(
                            new JacksonJsonRedisSerializer<>(ProductView.class))))
            .withCacheConfiguration(
                PRODUCT_PAGES,
                defaults
                    .entryTtl(properties.pageCacheTtl())
                    .serializeValuesWith(
                        SerializationPair.fromSerializer(
                            new JacksonJsonRedisSerializer<>(ProductPage.class))))
            .disableCreateOnMissingCache()
            .build();
    // Not a bean of its own, so run the initialisation Spring would otherwise do for us; without
    // it the configured caches are never created and getCache returns null.
    redis.afterPropertiesSet();
    // Order matters: the transaction-aware proxy defers writes to after commit, and the resilient
    // layer underneath stops a failure at that point from reaching the caller.
    return new TransactionAwareCacheManagerProxy(new ResilientCache.Manager(redis));
  }

  @Override
  public CacheErrorHandler errorHandler() {
    return new LoggingCacheErrorHandler(LogFactory.getLog("dev.commerceflow.catalog.cache"), false);
  }
}
