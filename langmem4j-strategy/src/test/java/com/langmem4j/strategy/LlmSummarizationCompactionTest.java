package com.langmem4j.strategy;

import com.langmem4j.core.memory.Memory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmSummarizationCompactionTest {

    private static Memory mem(String key, String value, String category) {
        return new Memory("ns", key, value,
                category != null ? Map.of("category", category) : null,
                null, 0, 0);
    }

    // Stub summarizer: returns the input unchanged (deterministic for tests)
    private static final Function<String, String> IDENTITY_SUMMARIZER = s -> s;

    // Stub summarizer: returns a fixed summary
    private static final Function<String, String> FIXED_SUMMARIZER = s -> "SUMMARY";

    // ── Constructor validation ─────────────────────────────

    @Test
    void constructor_null_function_throws() {
        assertThatThrownBy(() -> new LlmSummarizationCompaction((Function<String, String>) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");
    }

    // ── Single-element groups ──────────────────────────────

    @Test
    void compact_single_element_skips_LLM_call() {
        var input = List.of(mem("a", "only one", "food"));
        var policy = new LlmSummarizationCompaction(s -> {
            throw new AssertionError("LLM should not be called for single-element group");
        });
        var result = policy.compact("ns", input);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).value()).isEqualTo("only one");
    }

    // ── Multi-element grouping ─────────────────────────────

    @Test
    void compact_multi_group_produces_one_summary_per_group() {
        var input = List.of(
                mem("a", "Alice likes hot pot", "food"),
                mem("b", "Alice likes iced tea", "food"),
                mem("c", "Alice has a cat", "pet"),
                mem("d", "Alice has a dog", "pet")
        );
        var policy = new LlmSummarizationCompaction(FIXED_SUMMARIZER);
        var result = policy.compact("ns", input);

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(m -> m.value().equals("SUMMARY"));
        assertThat(result).allMatch(m -> Boolean.TRUE.equals(m.metadata().get("compacted")));
    }

    @Test
    void compact_single_in_group_stays_as_is() {
        var input = List.of(
                mem("a", "food item", "food"),
                mem("b", "pet item", "pet")  // different categories, each 1 element
        );
        var policy = new LlmSummarizationCompaction(FIXED_SUMMARIZER);
        var result = policy.compact("ns", input);

        // Each is single in its group → no LLM call, returned as-is
        assertThat(result).hasSize(2);
        assertThat(result).noneMatch(m -> m.metadata().containsKey("compacted"));
    }

    // ── Metadata preservation ──────────────────────────────

    @Test
    void compact_preserves_category_metadata() {
        var input = List.of(
                mem("a", "v1", "food"),
                mem("b", "v2", "food")
        );
        var policy = new LlmSummarizationCompaction(FIXED_SUMMARIZER);
        var result = policy.compact("ns", input);

        assertThat(result.get(0).metadata())
                .containsEntry("category", "food")
                .containsEntry("compacted", true);
    }

    // ── createdAt preservation ─────────────────────────────

    @Test
    void compact_preserves_earliest_createdAt() {
        long early = 1_000L;
        long late = 2_000L;
        var input = List.of(
                new Memory("ns", "a", "v1", Map.of("category", "x"), null, early, early),
                new Memory("ns", "b", "v2", Map.of("category", "x"), null, late, late)
        );
        var policy = new LlmSummarizationCompaction(FIXED_SUMMARIZER);
        var result = policy.compact("ns", input);

        assertThat(result.get(0).createdAt()).isEqualTo(early);
    }

    // ── Key naming ─────────────────────────────────────────

    @Test
    void compact_key_is_category_plus_compacted() {
        var input = List.of(
                mem("a", "v1", "food"),
                mem("b", "v2", "food")
        );
        var policy = new LlmSummarizationCompaction(FIXED_SUMMARIZER);
        var result = policy.compact("ns", input);

        assertThat(result.get(0).key()).isEqualTo("food_compacted");
    }

    // ── Default category ───────────────────────────────────

    @Test
    void compact_memories_without_category_use_default_group() {
        var input = List.of(
                mem("a", "v1", null),
                mem("b", "v2", null)
        );
        var policy = new LlmSummarizationCompaction(FIXED_SUMMARIZER);
        var result = policy.compact("ns", input);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).key()).isEqualTo("default_compacted");
        assertThat(result.get(0).metadata().get("category")).isEqualTo("default");
    }

    // ── Empty list ─────────────────────────────────────────

    @Test
    void compact_empty_list_returns_empty() {
        var policy = new LlmSummarizationCompaction(FIXED_SUMMARIZER);
        var result = policy.compact("ns", List.of());
        assertThat(result).isEmpty();
    }

    // ── LLM prompt verification ─────────────────────────────

    @Test
    void compact_sends_combined_values_to_LLM() {
        var input = List.of(
                mem("a", "hot pot", "food"),
                mem("b", "iced tea", "food")
        );

        // Capture the prompt sent to the LLM
        var capturedPrompt = new java.util.concurrent.atomic.AtomicReference<String>("");
        Function<String, String> capturingSummarizer = prompt -> {
            capturedPrompt.set(prompt);
            return "summary";
        };

        var policy = new LlmSummarizationCompaction(capturingSummarizer);
        policy.compact("ns", input);

        String prompt = capturedPrompt.get();
        assertThat(prompt).contains("hot pot");
        assertThat(prompt).contains("iced tea");
        assertThat(prompt).contains("Summarize");
    }

    // ── Identity summarizer (no-op LLM) ────────────────────

    @Test
    void compact_with_identity_summarizer_returns_combined_values() {
        var input = List.of(
                mem("a", "Alice likes hot pot", "food"),
                mem("b", "Alice likes iced tea", "food")
        );
        var policy = new LlmSummarizationCompaction(IDENTITY_SUMMARIZER);
        var result = policy.compact("ns", input);

        // Identity summarizer returns the full prompt (including template text)
        // The key thing: both original values are present in the result
        assertThat(result).hasSize(1);
        assertThat(result.get(0).value()).contains("hot pot");
        assertThat(result.get(0).value()).contains("iced tea");
    }

    // ── Does not mutate input ───────────────────────────────

    @Test
    void compact_does_not_mutate_input_list() {
        var input = List.of(
                mem("a", "v1", "food"),
                mem("b", "v2", "food")
        );
        var policy = new LlmSummarizationCompaction(FIXED_SUMMARIZER);
        policy.compact("ns", input);

        assertThat(input).hasSize(2);
        assertThat(input.get(0).value()).isEqualTo("v1");
        assertThat(input.get(1).value()).isEqualTo("v2");
    }

    // ── lastAccessedAt refreshed ───────────────────────────

    @Test
    void compact_refreshes_lastAccessedAt() {
        long old = 1_000L;
        var input = List.of(
                new Memory("ns", "a", "v1", Map.of("category", "x"), null, old, old),
                new Memory("ns", "b", "v2", Map.of("category", "x"), null, old, old)
        );
        long before = System.currentTimeMillis();
        var policy = new LlmSummarizationCompaction(FIXED_SUMMARIZER);
        var result = policy.compact("ns", input);
        long after = System.currentTimeMillis();

        assertThat(result.get(0).lastAccessedAt()).isBetween(before, after + 1000);
    }
}
