package com.langmem4j.core.metrics;

import java.time.Duration;

/**
 * SPI for recording operational metrics from {@link com.langmem4j.core.manager.MemoryManager}.
 * <p>
 * Pure Java, zero framework dependencies — implementations decide what to do
 * with the events. The default methods are all no-ops, so implementers only
 * override what they care about, and {@link #NOOP} is the default when no
 * recorder is configured (zero overhead beyond a call that returns
 * immediately).
 * <p>
 * The production implementation is
 * {@code MicrometerMemoryMetricsRecorder} in the {@code langmem4j-observability}
 * module (kept out of core so core never depends on Micrometer).
 *
 * <h3>Event semantics</h3>
 * <ul>
 *   <li>{@code recordAdd} — fired once per {@code add()} / {@code addAll()}
 *       batch, with the store-level outcome.</li>
 *   <li>{@code recordSearch} — fired once per search call with the wall-clock
 *       duration; a store exception still records (success=false) before
 *       rethrowing.</li>
 *   <li>{@code recordGet} — fired per exact-key lookup; {@code hit} enables
 *       hit-rate dashboards.</li>
 *   <li>{@code recordCompact} — fired per non-noop {@code compact()} with the
 *       policy's class name.</li>
 *   <li>{@code recordDecayFactor} — fired per surviving search result when a
 *       decay policy is active (distribution of memory freshness).</li>
 *   <li>{@code recordNamespaceResolve} — fired on every namespace resolution;
 *       source is {@code fixed} (static default namespace), {@code resolver}
 *       (runtime resolver won), or {@code fallback} (resolver returned nothing,
 *       default namespace used).</li>
 * </ul>
 */
public interface MemoryMetricsRecorder {

    /**
     * Records a memory write attempt (single {@code add} or one
     * {@code addAll} batch).
     *
     * @param namespace namespace the write targeted
     * @param success   false when the store threw, in which case the exception
     *                  is re-thrown after recording
     */
    default void recordAdd(String namespace, boolean success) {}

    /**
     * Records a search call with its wall-clock duration.
     *
     * @param namespace namespace that was searched
     * @param duration  elapsed time of the store call + decay re-ranking
     * @param success   false when the store threw
     */
    default void recordSearch(String namespace, Duration duration, boolean success) {}

    /**
     * Records an exact-key lookup.
     *
     * @param namespace namespace that was queried
     * @param hit       true when a memory was found
     */
    default void recordGet(String namespace, boolean hit) {}

    /**
     * Records a non-noop compaction run.
     *
     * @param namespace namespace that was compacted
     * @param policy    compaction policy class name (e.g. {@code CategoryGroupCompactionPolicy})
     * @param duration  elapsed time of the whole run
     * @param success   false when the run threw
     */
    default void recordCompact(String namespace, String policy, Duration duration, boolean success) {}

    /**
     * Records the decay factor of a search result that survived pruning.
     * Only fired when a decay policy is active.
     *
     * @param namespace   namespace of the memory
     * @param decayFactor freshness in [0, 1] — 1 = brand new, → 0 = stale
     */
    default void recordDecayFactor(String namespace, float decayFactor) {}

    /**
     * Records one namespace resolution.
     *
     * @param source {@code fixed} | {@code resolver} | {@code fallback}
     */
    default void recordNamespaceResolve(String source) {}

    /** A recorder that does nothing — the default when none is configured. */
    MemoryMetricsRecorder NOOP = new MemoryMetricsRecorder() {};
}
