package com.langmem4j.strategy;

import com.langmem4j.core.memory.Memory;
import com.langmem4j.core.memory.MemoryCompactionPolicy;
import dev.langchain4j.model.chat.ChatModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * LLM-driven compaction policy — groups memories by {@code metadata.category},
 * sends each group's values to a {@link ChatModel} for summarization,
 * and replaces the fragments with a single concise record per group.
 *
 * <pre>{@code
 * MemoryManager manager = MemoryManager.inMemory()
 *         .withCompactionPolicy(new LlmSummarizationCompaction(myChatModel))
 *         .build();
 *
 * manager.add("food_1", "Alice likes hot pot",       Map.of("category", "food"));
 * manager.add("food_2", "Alice also likes iced tea",  Map.of("category", "food"));
 * manager.add("pet_1",  "Alice has a cat named Luna", Map.of("category", "pet"));
 *
 * manager.compact("user_alice");
 * // → 2 memories: "food_compacted" (LLM-summarized) + "pet_1" (single, not compacted)
 * }</pre>
 *
 * <h3>Contract</h3>
 * <ul>
 *   <li>Groups by {@code metadata.get("category")} (defaults to {@code "default"})</li>
 *   <li>Single-element groups are returned as-is (no LLM call wasted)</li>
 *   <li>Multi-element groups: values joined with newline, sent to LLM for summarization</li>
 *   <li>Compacted key = {@code category + "_compacted"}</li>
 *   <li>Metadata: {@code category=original, compacted=true}</li>
 *   <li>createdAt = earliest in group; lastAccessedAt = now</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * The policy is stateless; thread-safety depends on the underlying {@link ChatModel}.
 */
public class LlmSummarizationCompaction implements MemoryCompactionPolicy {

    private static final String SUMMARIZE_PROMPT_TEMPLATE =
            "Summarize these into one concise factual statement. Do not add new information. " +
            "Preserve all facts from the original entries:\n%s";

    private final Function<String, String> summarizer;

    /**
     * Creates a compaction policy backed by a LangChain4j {@link ChatModel}.
     *
     * @param model the chat model used for summarization (must not be null)
     */
    public LlmSummarizationCompaction(ChatModel model) {
        if (model == null) {
            throw new IllegalArgumentException("ChatModel must not be null");
        }
        this.summarizer = model::chat;
    }

    /**
     * Creates a compaction policy backed by an arbitrary summarization function.
     * Use this if you're not on LangChain4j (e.g. Spring AI, custom HTTP client).
     *
     * @param summarizer a function that takes the combined values and returns a summary
     */
    public LlmSummarizationCompaction(Function<String, String> summarizer) {
        if (summarizer == null) {
            throw new IllegalArgumentException("summarizer function must not be null");
        }
        this.summarizer = summarizer;
    }

    @Override
    public List<Memory> compact(String namespace, List<Memory> candidates) {
        if (candidates.size() <= 1) return new ArrayList<>(candidates);

        // 1. Group by metadata "category" (default = "default")
        Map<String, List<Memory>> groups = candidates.stream()
                .collect(Collectors.groupingBy(m ->
                        (String) m.metadata().getOrDefault("category", "default")));

        List<Memory> result = new ArrayList<>();

        for (Map.Entry<String, List<Memory>> entry : groups.entrySet()) {
            String category = entry.getKey();
            List<Memory> group = entry.getValue();

            // 2. Single-element groups: skip compaction
            if (group.size() <= 1) {
                result.addAll(group);
                continue;
            }

            // 3. Combine values and send to LLM for summarization
            String combined = group.stream()
                    .map(Memory::value)
                    .collect(Collectors.joining("\n"));

            String prompt = String.format(SUMMARIZE_PROMPT_TEMPLATE, combined);
            String summary = summarizer.apply(prompt);

            // 4. Earliest createdAt in group
            long earliest = group.stream()
                    .mapToLong(Memory::createdAt)
                    .min()
                    .orElse(System.currentTimeMillis());

            // 5. Build compacted memory
            Map<String, Object> meta = new HashMap<>();
            meta.put("category", category);
            meta.put("compacted", true);

            result.add(new Memory(
                    namespace,
                    category + "_compacted",
                    summary,
                    meta,
                    null,   // embedding will be filled by MemoryManager if generator is configured
                    earliest,
                    System.currentTimeMillis()
            ));
        }

        return result;
    }
}
