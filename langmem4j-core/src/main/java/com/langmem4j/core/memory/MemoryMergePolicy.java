package com.langmem4j.core.memory;

import java.util.HashMap;
import java.util.Map;

/**
 * Strategy for merging an incoming memory with an existing one at the same
 * {@code namespace + key}.
 * <p>
 * When {@link com.langmem4j.core.manager.MemoryManager} is configured with a
 * merge policy, every {@code add()} and {@code addAll()} call first checks
 * whether a memory already exists at that key. If it does, the policy is
 * invoked to produce a merged record; otherwise the incoming memory is
 * stored as-is.
 *
 * <pre>{@code
 * MemoryManager manager = MemoryManager.inMemory()
 *         .withMergePolicy(MemoryMergePolicy.keyMerge())
 *         .build();
 *
 * // First write creates the memory
 * manager.add("pref", "Alice likes hot pot");
 * // Second write merges — keeps the longer value, unions metadata
 * manager.add("pref", "Alice likes spicy hot pot", Map.of("source", "diary"));
 * }</pre>
 *
 * <h3>Design notes</h3>
 * <ul>
 *   <li>The policy is a pure function — it receives both records and returns
 *       a new merged record; it must not mutate either input.</li>
 *   <li>Implementations should preserve the earliest {@code createdAt} to
 *       maintain the memory's original creation time.</li>
 *   <li>Implementations should update {@code lastAccessedAt} to
 *       {@code System.currentTimeMillis()} since the merge itself constitutes
 *       an access.</li>
 *   <li>Implementations must be thread-safe (stateless is best).</li>
 * </ul>
 *
 * <h3>TODO: true semantic dedup</h3>
 * The current {@link #keyMerge()} only triggers when two memories share the
 * same {@code namespace + key}. True semantic dedup would search by embedding
 * similarity (cosine &gt; threshold) and merge near-duplicates regardless of key.
 * This requires a store round-trip per incoming memory and is left for a future
 * implementation.
 */
@FunctionalInterface
public interface MemoryMergePolicy {

    /**
     * Merges an existing memory with an incoming one.
     *
     * @param existing the memory currently stored at this key (never null)
     * @param incoming the new memory being written to the same key (never null)
     * @return the merged memory to store
     */
    Memory merge(Memory existing, Memory incoming);

    /**
     * No merge — the incoming memory always overwrites the existing one.
     * This is the default policy when none is configured (classic upsert semantics).
     */
    MemoryMergePolicy NONE = (existing, incoming) -> incoming;

    /**
     * Key-based merge — combines two memories at the same key into one.
     * <p>
     * Merges by:
     * <ul>
     *   <li><b>value</b>: keeps the longer of the two values (assumed to be
     *       the more detailed / superset)</li>
     *   <li><b>metadata</b>: unions both maps; incoming keys override existing
     *       keys on conflict</li>
     *   <li><b>createdAt</b>: preserves the earliest (original creation time)</li>
     *   <li><b>lastAccessedAt</b>: set to now (the merge is an access)</li>
     *   <li><b>embeddingVector</b>: uses the incoming vector if present
     *       (freshly computed), otherwise falls back to the existing one</li>
     * </ul>
     *
     * @return a merge policy that de-duplicates and enriches memories by key
     */
    static MemoryMergePolicy keyMerge() {
        return (existing, incoming) -> {
            String value = incoming.value().length() >= existing.value().length()
                    ? incoming.value() : existing.value();

            Map<String, Object> mergedMeta = new HashMap<>(existing.metadata());
            mergedMeta.putAll(incoming.metadata());

            long createdAt = Math.min(existing.createdAt(), incoming.createdAt());
            long lastAccessedAt = System.currentTimeMillis();

            float[] embedding = incoming.embeddingVector() != null
                    ? incoming.embeddingVector() : existing.embeddingVector();

            return new Memory(
                    existing.namespace(),
                    existing.key(),
                    value,
                    mergedMeta,
                    embedding,
                    createdAt,
                    lastAccessedAt
            );
        };
    }
}
