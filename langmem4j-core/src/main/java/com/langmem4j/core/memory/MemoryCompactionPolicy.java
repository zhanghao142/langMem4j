package com.langmem4j.core.memory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Strategy for compacting (summarizing) a set of memory fragments into fewer,
 * higher-density records.
 * <p>
 * When a namespace accumulates too many fragmented memories (e.g. 100 rounds of
 * conversation → 100 short entries), search results overflow the LLM's context
 * window. A compaction policy groups related memories and replaces them with a
 * smaller number of summarized records — preserving information while cutting
 * token count.
 *
 * <pre>{@code
 * MemoryManager manager = MemoryManager.inMemory()
 *         .withCompactionPolicy(MemoryCompactionPolicy.categoryGroup())
 *         .build();
 *
 * // After 50 rounds of conversation…
 * manager.compact("user_alice");   // 50 fragments → ~5 summarized memories
 * }</pre>
 *
 * <h3>Design notes</h3>
 * <ul>
 *   <li>The policy is a pure function — it receives the full list of candidate
 *       memories and returns a replacement list. It must not mutate inputs.</li>
 *   <li>The returned list may be shorter, equal, or (rarely) longer than the
 *       input — the contract is "these memories replace those memories".</li>
 *   <li>Implementations are typically LLM-driven (summarization), but the SPI
 *       itself lives in core with zero framework deps. The LLM-backed
 *       implementation belongs in a separate module (e.g. langmem4j-strategy).</li>
 *   <li>Thread-safety: implementations must be stateless or thread-safe.</li>
 * </ul>
 *
 * <h3>Contract</h3>
 * <ul>
 *   <li>Single-element groups are returned as-is (no compaction).</li>
 *   <li>Compacted memories should carry {@code compacted=true} in metadata.</li>
 *   <li>Compacted memories should preserve the earliest {@code createdAt} of
 *       the group and set {@code lastAccessedAt} to now.</li>
 * </ul>
 *
 * @see com.langmem4j.core.manager.MemoryManager#compact(String)
 */
@FunctionalInterface
public interface MemoryCompactionPolicy {

    /**
     * Compacts a list of memory fragments into fewer, denser records.
     *
     * @param namespace  the namespace all candidates belong to (never null)
     * @param candidates all memories in the namespace (never null, may be empty)
     * @return the replacement list (may be identical if no compaction occurred)
     */
    List<Memory> compact(String namespace, List<Memory> candidates);

    /**
     * No compaction — returns the input unchanged. This is the default when
     * no policy is configured, ensuring zero upgrade friction.
     */
    MemoryCompactionPolicy NONE = (namespace, candidates) -> candidates;

    /**
     * Category-based grouping — groups memories by their {@code metadata.category}
     * key and concatenates values within each group.
     * <p>
     * This is a <b>pure Java</b> compaction (no LLM): it simply joins the values
     * of same-category memories into a single record. Useful when you don't have
     * an LLM available, or as a deterministic fallback.
     *
     * <ul>
     *   <li>Groups by {@code metadata.get("category")} (defaults to {@code "default"} if absent)</li>
     *   <li>Single-element groups are kept as-is</li>
     *   <li>Multi-element groups: values joined with {@code "; "}</li>
     *   <li>Compacted memory key = {@code category + "_compacted"}</li>
     *   <li>Metadata: {@code category=original, compacted=true}</li>
     *   <li>createdAt = earliest in group; lastAccessedAt = now</li>
     * </ul>
     *
     * @return a deterministic, LLM-free compaction policy
     */
    static MemoryCompactionPolicy categoryGroup() {
        return (namespace, candidates) -> {
            if (candidates.size() <= 1) return new ArrayList<>(candidates);

            // Group by metadata "category" (default = "default")
            Map<String, List<Memory>> groups = candidates.stream()
                    .collect(Collectors.groupingBy(m ->
                            (String) m.metadata().getOrDefault("category", "default")));

            List<Memory> result = new ArrayList<>();
            for (Map.Entry<String, List<Memory>> entry : groups.entrySet()) {
                String category = entry.getKey();
                List<Memory> group = entry.getValue();

                if (group.size() <= 1) {
                    result.addAll(group);
                    continue;
                }

                // Concatenate values with "; " separator
                String combined = group.stream()
                        .map(Memory::value)
                        .collect(Collectors.joining("; "));

                // Earliest createdAt in group
                long earliest = group.stream()
                        .mapToLong(Memory::createdAt)
                        .min()
                        .orElse(System.currentTimeMillis());

                Map<String, Object> meta = new HashMap<>();
                meta.put("category", category);
                meta.put("compacted", true);

                result.add(new Memory(
                        namespace,
                        category + "_compacted",
                        combined,
                        meta,
                        null,
                        earliest,
                        System.currentTimeMillis()
                ));
            }
            return result;
        };
    }
}
