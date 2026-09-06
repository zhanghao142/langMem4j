package com.langmem4j.spring;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tiny bounded TTL cache for namespace resolution results, keyed by the
 * inputs the pattern actually depends on (in practice: the principal).
 * <p>
 * Only patterns that reference <strong>nothing but</strong> the
 * {@code principal} variable are cached — such results are a pure function
 * of the principal, so a hit can never serve another tenant's namespace.
 * Patterns that read request headers are evaluated on every call because
 * there is no safe, cheap cache key for them.
 * <p>
 * Implementation notes: a synchronized {@code LinkedHashMap} in access order
 * (simple LRU). Critical sections are a few nanoseconds; the lock is
 * uncontended in the common single-request-thread case.
 */
final class NamespaceResultCache {

    private record CacheEntry(String value, long expiresAtMillis) {}

    private final int maxSize;
    private final long ttlMillis;
    private final Map<String, CacheEntry> entries;

    NamespaceResultCache(int maxSize, long ttlMillis) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be positive, got " + maxSize);
        }
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("ttlMillis must be positive, got " + ttlMillis);
        }
        this.maxSize = maxSize;
        this.ttlMillis = ttlMillis;
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                return size() > NamespaceResultCache.this.maxSize;
            }
        };
    }

    /**
     * @return the cached namespace for this key, or null when absent/expired
     */
    synchronized String get(String key) {
        CacheEntry entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() >= entry.expiresAtMillis()) {
            entries.remove(key);
            return null;
        }
        return entry.value();
    }

    synchronized void put(String key, String value) {
        entries.put(key, new CacheEntry(value, System.currentTimeMillis() + ttlMillis));
    }

    synchronized int size() {
        return entries.size();
    }
}
