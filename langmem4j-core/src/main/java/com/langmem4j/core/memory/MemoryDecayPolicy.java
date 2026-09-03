package com.langmem4j.core.memory;

/**
 * Strategy for computing a relevance decay factor for a memory based on its age.
 * <p>
 * A decay factor of {@code 1.0} means the memory is fully relevant; {@code 0.0}
 * means it has completely decayed and should be pruned. The factor is typically
 * used by {@link com.langmem4j.core.manager.MemoryManager} during
 * {@code search()} to filter and re-rank stale memories before returning results.
 *
 * <pre>{@code
 * MemoryManager manager = MemoryManager.inMemory()
 *         .withDecayPolicy(MemoryDecayPolicy.exponential())   // 7-day half-life
 *         .build();
 * }</pre>
 *
 * <h3>Design notes</h3>
 * <ul>
 *   <li>The policy is a pure function — no side effects, no state.</li>
 *   <li>{@code lastAccessedAt} allows "refreshing" a memory by updating it,
 *       pushing back the decay clock. See
 *       {@link Memory#withLastAccessedAt(long)}.
 *       {@link com.langmem4j.core.manager.MemoryManager#get} refreshes
 *       {@code lastAccessedAt} on every read when a decay policy is active.</li>
 *   <li>Implementations must be thread-safe (stateless is best).</li>
 * </ul>
 */
@FunctionalInterface
public interface MemoryDecayPolicy {

    /**
     * Computes the decay factor for a memory.
     *
     * @param createdAt        epoch millis when the memory was first written
     * @param lastAccessedAt   epoch millis when the memory was last read/merged
     * @param now              current epoch millis (passed by caller for testability)
     * @return a float in {@code [0.0, 1.0]} — 1.0 = no decay, 0.0 = fully decayed
     */
    float decayFactor(long createdAt, long lastAccessedAt, long now);

    /**
     * The minimum decay factor below which a memory is considered "dead"
     * and pruned from search results. Default is {@code 0.01f} (1% relevance).
     * <p>
     * For the default 7-day half-life, this corresponds to ~46 days
     * (6.6 half-lives). For shorter half-lives (e.g. 1 hour), consider
     * overriding this to a lower value (e.g. {@code 0.001f}) to avoid
     * premature pruning.
     * <p>
     * To customise, implement this interface as an anonymous class
     * and override this method.
     *
     * @return the minimum decay factor for a memory to survive pruning
     */
    default float pruneThreshold() {
        return 0.01f;
    }

    /**
     * No decay — every memory stays at full relevance regardless of age.
     * This is the default policy when none is configured.
     */
    MemoryDecayPolicy NONE = (createdAt, lastAccessedAt, now) -> 1.0f;

    /**
     * Exponential decay with a 7-day half-life (default).
     * <p>
     * A memory's relevance halves every 7 days since its last access:
     * <pre>
     *   factor = 0.5 ^ (age / halfLife)
     * </pre>
     * where {@code age = now - lastAccessedAt}.
     *
     * @return a decay policy that halves relevance every 7 days
     */
    static MemoryDecayPolicy exponential() {
        return exponential(7L * 24 * 60 * 60 * 1000); // 7 days in millis
    }

    /**
     * Exponential decay with a custom half-life.
     *
     * @param halfLifeMs the time in millis for relevance to halve;
     *                   must be positive (a non-positive value yields no decay)
     * @return a decay policy that halves relevance every {@code halfLifeMs}
     */
    static MemoryDecayPolicy exponential(long halfLifeMs) {
        return (createdAt, lastAccessedAt, now) -> {
            long age = now - lastAccessedAt;
            if (age <= 0 || halfLifeMs <= 0) return 1.0f;
            return (float) Math.pow(0.5, (double) age / halfLifeMs);
        };
    }
}
