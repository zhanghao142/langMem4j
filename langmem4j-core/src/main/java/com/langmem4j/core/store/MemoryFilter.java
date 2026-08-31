package com.langmem4j.core.store;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Set of constraints used by {@link MemoryStore#search} to narrow down
 * results before / alongside the relevance ranking.
 * <p>
 * Two kinds of predicates are currently supported:
 * <ul>
 *   <li><b>{@link #metadataMatch()} (AND semantics):</b> a memory's
 *       {@link com.langmem4j.core.memory.Memory#metadata()} must contain
 *       every key-value pair in the map. Comparing uses
 *       {@link Objects#equals(Object, Object)} — put a {@code null} value
 *       to match only metadata where that key is <em>absent</em> or the
 *       value itself is null.</li>
 *   <li><b>{@link #minScore()}:</b> drop any result whose similarity score
 *       is strictly below this threshold. Only meaningful for vector-backed
 *       stores; substring / deterministic stores should ignore it.</li>
 * </ul>
 * Instances are immutable and nullable-safe to construct (empty matches
 * are equivalent to {@link #NONE}).
 *
 * <pre>{@code
 * MemoryFilter filter = MemoryFilter.builder()
 *         .metadata("category", "preference")
 *         .metadata("source",   "user")
 *         .minScore(0.5f)
 *         .build();
 * }</pre>
 */
public final class MemoryFilter {

    /**
     * Canonical no-op filter: search returns the unconstrained top-N.
     * Prefer this to {@code null} when passing to the SPI.
     */
    public static final MemoryFilter NONE = new MemoryFilter(Map.of(), Float.NaN);

    private final Map<String, Object> metadataMatch;
    private final float minScore;

    private MemoryFilter(Map<String, Object> metadataMatch, float minScore) {
        this.metadataMatch = metadataMatch;
        this.minScore = minScore;
    }

    // ----- factories / builder -----

    /**
     * Shortcut for a filter that only imposes metadata equality, with
     * AND semantics across all entries.
     */
    public static MemoryFilter metadata(Map<String, Object> requirements) {
        if (requirements == null || requirements.isEmpty()) return NONE;
        return new MemoryFilter(
                Collections.unmodifiableMap(new HashMap<>(requirements)),
                Float.NaN);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<String, Object> metadata = new HashMap<>();
        private float minScore = Float.NaN;

        public Builder metadata(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }

        public Builder metadata(Map<String, Object> entries) {
            if (entries != null) this.metadata.putAll(entries);
            return this;
        }

        public Builder minScore(float min) {
            this.minScore = min;
            return this;
        }

        public MemoryFilter build() {
            if (metadata.isEmpty() && Float.isNaN(minScore)) return NONE;
            return new MemoryFilter(
                    Collections.unmodifiableMap(new HashMap<>(metadata)),
                    minScore);
        }
    }

    // ----- accessors -----

    /** Metadata requirements (AND semantics). Always non-null; may be empty. */
    public Map<String, Object> metadataMatch() {
        return metadataMatch;
    }

    /**
     * Minimum similarity score for vector stores. Returns {@link Float#NaN}
     * when the caller expressed no preference (i.e. every result is kept).
     */
    public float minScore() {
        return minScore;
    }

    public boolean hasMetadataMatch() {
        return !metadataMatch.isEmpty();
    }

    public boolean hasMinScore() {
        return !Float.isNaN(minScore);
    }

    // ----- default predicate used by non-vector stores -----

    /**
     * Returns {@code true} if the given memory satisfies every metadata
     * key in this filter. Used directly by {@link MemoryStore}
     * implementations that do not have a native filter DSL; vector stores
     * (e.g. Qdrant) should push it down to the DB instead.
     */
    public boolean matchesMetadata(Map<String, Object> memoryMetadata) {
        if (!hasMetadataMatch()) return true;
        Map<String, Object> md = memoryMetadata == null ? Map.of() : memoryMetadata;
        for (Map.Entry<String, Object> required : metadataMatch.entrySet()) {
            Object actual = md.get(required.getKey());
            if (!Objects.equals(actual, required.getValue())) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MemoryFilter that)) return false;
        return Float.compare(that.minScore, minScore) == 0
                && metadataMatch.equals(that.metadataMatch);
    }

    @Override
    public int hashCode() {
        return Objects.hash(metadataMatch, minScore);
    }

    @Override
    public String toString() {
        return "MemoryFilter{metadataMatch=" + metadataMatch
                + ", minScore=" + (hasMinScore() ? minScore : "N/A") + '}';
    }
}
