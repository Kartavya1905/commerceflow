# ADR-0009: Catalog cache is best-effort, evicted after commit, and never used for checkout

- Status: Accepted
- Date: 2026-11-01

## Context
catalog-service caches public product reads in Redis (cache-aside). Three questions decide whether that cache can ever cause harm: when does an edit clear it, what happens when Redis is down, and which reads may use it.

While building Milestone 3 we measured the actual behaviour instead of assuming it:
- Spring's `TransactionAwareCacheDecorator` defers puts and evictions to an after-commit callback and does **not** catch exceptions there. Spring's `CacheErrorHandler` does not cover that callback either (`ResilientCacheTest` reproduces this).
- Spring Data Redis 4.1's cache writer, against an unreachable server, **throws on reads but returns normally from writes and deletes** (`RedisDownIT.deadRedisFailsReadsLoudlyButWritesSilently` pins this down).

## Decision
1. **Evict after commit.** The cache manager is transaction-aware, so a rolled-back edit never clears the cache and a reader cannot re-cache the old value between the update and the commit.
2. **Best-effort cache.** Read errors go through a logging `CacheErrorHandler`. Deferred writes go through `ResilientCache`, which logs and skips failures, so no cache failure can turn a committed write into a 500, whatever the cache library does on its next upgrade. Redis timeouts are 500 ms, not Lettuce's 60 s default, and Redis is excluded from the readiness probe.
3. **Checkout never reads the cache.** `/internal/v1/prices`, used by order-service to price an order, always reads PostgreSQL.
4. **Bounded staleness.** Product entries live 60 s and listing pages 30 s. Clearing pages uses SCAN, not KEYS.

## Consequences
- An eviction lost while Redis is unreachable can leave a product page showing an old price for at most 60 s. Customers are always charged the database price, because checkout does not use the cache.
- During a Redis outage every read goes to PostgreSQL; latency rises, errors do not.
- A future change that makes the Redis writer throw is caught by `RedisDownIT`, and `ResilientCache` keeps the behaviour above unchanged.

## Alternatives
- Evict before commit: simpler, but a concurrent reader can re-cache the old row before the commit lands, leaving the cache stale for a full TTL.
- No cache: simplest and always fresh; rejected because NFR-05 (p95 < 300 ms at 500 users) is a read-heavy target that the cache exists to protect. M12 measures whether it is actually needed.
- Event-driven invalidation (catalog publishes ProductUpdated, a listener evicts): needed when several services cache catalog data; here only catalog-service does.
