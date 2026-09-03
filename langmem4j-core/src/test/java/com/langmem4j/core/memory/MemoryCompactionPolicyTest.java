package com.langmem4j.core.memory;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryCompactionPolicyTest {

    private static Memory mem(String key, String value, String category) {
        return new Memory("ns", key, value,
                category != null ? Map.of("category", category) : null,
                null, 0, 0);
    }

    // ── NONE ──────────────────────────────────────────────

    @Test
    void none_returns_input_unchanged() {
        var input = List.of(mem("a", "v1", "food"), mem("b", "v2", "food"));
        var result = MemoryCompactionPolicy.NONE.compact("ns", input);
        assertThat(result).isSameAs(input);
    }

    @Test
    void none_with_empty_list_is_noop() {
        var result = MemoryCompactionPolicy.NONE.compact("ns", List.of());
        assertThat(result).isEmpty();
    }

    // ── categoryGroup ─────────────────────────────────────

    @Test
    void categoryGroup_single_element_is_not_compacted() {
        var input = List.of(mem("a", "only one", "food"));
        var result = MemoryCompactionPolicy.categoryGroup().compact("ns", input);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).key()).isEqualTo("a");
        assertThat(result.get(0).value()).isEqualTo("only one");
    }

    @Test
    void categoryGroup_multi_element_concatenates_values() {
        var input = List.of(
                mem("a", "Alice likes hot pot", "food"),
                mem("b", "Alice likes iced tea", "food")
        );
        var result = MemoryCompactionPolicy.categoryGroup().compact("ns", input);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).key()).isEqualTo("food_compacted");
        assertThat(result.get(0).value()).contains("hot pot");
        assertThat(result.get(0).value()).contains("iced tea");
    }

    @Test
    void categoryGroup_preserves_category_metadata() {
        var input = List.of(
                mem("a", "v1", "food"),
                mem("b", "v2", "food")
        );
        var result = MemoryCompactionPolicy.categoryGroup().compact("ns", input);
        assertThat(result.get(0).metadata())
                .containsEntry("category", "food")
                .containsEntry("compacted", true);
    }

    @Test
    void categoryGroup_preserves_earliest_createdAt() {
        long early = 1_000L;
        long late = 2_000L;
        var input = List.of(
                new Memory("ns", "a", "v1", Map.of("category", "x"), null, early, early),
                new Memory("ns", "b", "v2", Map.of("category", "x"), null, late, late)
        );
        var result = MemoryCompactionPolicy.categoryGroup().compact("ns", input);
        assertThat(result.get(0).createdAt()).isEqualTo(early);
    }

    @Test
    void categoryGroup_multiple_groups_produce_multiple_compacted() {
        var input = List.of(
                mem("a", "hot pot", "food"),
                mem("b", "iced tea", "food"),
                mem("c", "cat named Luna", "pet"),
                mem("d", "lives in Shanghai", "pet")
        );
        var result = MemoryCompactionPolicy.categoryGroup().compact("ns", input);
        assertThat(result).hasSize(2);
        // Both compacted memories should have compacted=true
        assertThat(result).allMatch(m -> Boolean.TRUE.equals(m.metadata().get("compacted")));
    }

    @Test
    void categoryGroup_single_group_stays_as_is() {
        var input = List.of(
                mem("a", "only food memory", "food"),
                mem("b", "another food", "pet")  // different category → different group
        );
        var result = MemoryCompactionPolicy.categoryGroup().compact("ns", input);
        // Each is single in its group → not compacted
        assertThat(result).hasSize(2);
        assertThat(result).noneMatch(m -> m.metadata().containsKey("compacted"));
    }

    @Test
    void categoryGroup_empty_list_returns_empty() {
        var result = MemoryCompactionPolicy.categoryGroup().compact("ns", List.of());
        assertThat(result).isEmpty();
    }

    @Test
    void categoryGroup_memories_without_category_default_group() {
        var input = List.of(
                mem("a", "v1", null),
                mem("b", "v2", null)
        );
        var result = MemoryCompactionPolicy.categoryGroup().compact("ns", input);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).key()).isEqualTo("default_compacted");
    }

    @Test
    void categoryGroup_does_not_mutate_input() {
        var input = List.of(
                mem("a", "v1", "food"),
                mem("b", "v2", "food")
        );
        MemoryCompactionPolicy.categoryGroup().compact("ns", input);
        // Input list should still have original 2 elements
        assertThat(input).hasSize(2);
        assertThat(input.get(0).value()).isEqualTo("v1");
    }

    @Test
    void categoryGroup_lastAccessedAt_refreshed_to_now() {
        long old = 1_000L;
        var input = List.of(
                new Memory("ns", "a", "v1", Map.of("category", "x"), null, old, old),
                new Memory("ns", "b", "v2", Map.of("category", "x"), null, old, old)
        );
        long before = System.currentTimeMillis();
        var result = MemoryCompactionPolicy.categoryGroup().compact("ns", input);
        long after = System.currentTimeMillis();
        assertThat(result.get(0).lastAccessedAt()).isBetween(before, after + 1000);
    }
}
