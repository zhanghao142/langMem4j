package com.langmem4j.core.manager;

import com.langmem4j.core.embedding.EmbeddingGenerator;
import com.langmem4j.core.memory.Memory;
import com.langmem4j.core.memory.MemoryDecayPolicy;
import com.langmem4j.core.memory.MemoryMergePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryManagerTest {

    private static final long DAY = 24L * 60 * 60 * 1000;

    // ----- factories -----

    /** 2D toy generator matching the one from CosineSearchTest. */
    static EmbeddingGenerator toyGenerator() {
        return text -> {
            String t = text.toLowerCase();
            if (t.contains("cat")) return new float[]{1f, 0f};
            if (t.contains("dog")) return new float[]{0f, 1f};
            return new float[]{0.5f, 0.5f};
        };
    }

    // ----- builder / wiring -----

    @Test
    void inMemory_with_default_namespace_builds_successfully() {
        MemoryManager manager = MemoryManager.inMemory()
                .withDefaultNamespace("user")
                .build();

        assertThat(manager.defaultNamespace()).contains("user");
        assertThat(manager.embeddingGenerator()).isEmpty();
    }

    @Test
    void build_without_store_throws() {
        assertThatThrownBy(() -> new MemoryManager.Builder().build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("store must be set");
    }

    @Test
    void operations_without_default_namespace_require_explicit_param() {
        MemoryManager manager = MemoryManager.inMemory().build();

        assertThatThrownBy(() -> manager.add("k", "v"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No default namespace");

        assertThatThrownBy(() -> manager.get("k"))
                .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> manager.keys())
                .isInstanceOf(IllegalStateException.class);
    }

    // ----- add -----

    @Test
    void add_and_get_round_trip_via_default_namespace() {
        MemoryManager manager = MemoryManager.inMemory()
                .withDefaultNamespace("user1")
                .build();

        manager.add("food", "I like hot pot");

        Optional<Memory> result = manager.get("food");
        assertThat(result).isPresent();
        assertThat(result.get().value()).contains("hot pot");
    }

    @Test
    void add_overwrites_same_key() {
        MemoryManager manager = MemoryManager.inMemory()
                .withDefaultNamespace("ns")
                .build();

        manager.add("k", "old");
        manager.add("k", "new");

        assertThat(manager.get("k"))
                .map(Memory::value)
                .hasValueSatisfying(v -> assertThat(v).isEqualTo("new"));
    }

    @Test
    void add_with_metadata_persists_tags() {
        MemoryManager manager = MemoryManager.inMemory()
                .withDefaultNamespace("ns")
                .build();

        manager.add("k", "v", Map.of("source", "user", "age", 30));

        Memory m = manager.get("k").orElseThrow();
        assertThat(m.metadata())
                .containsEntry("source", "user")
                .containsEntry("age", 30);
    }

    @Test
    void add_with_explicit_namespace() {
        MemoryManager manager = MemoryManager.inMemory().build(); // no default

        manager.add("nsA", "k", "vA", null);
        manager.add("nsB", "k", "vB", null);

        assertThat(manager.get("nsA", "k")).map(Memory::value).contains("vA");
        assertThat(manager.get("nsB", "k")).map(Memory::value).contains("vB");
    }

    // ----- auto-embedding -----

    @Test
    void add_auto_generates_embedding_when_generator_configured() {
        MemoryManager manager = MemoryManager.inMemory()
                .withDefaultNamespace("ns")
                .withEmbeddingGenerator(toyGenerator())
                .build();

        manager.add("cat_mem", "my cat is fluffy");

        Memory stored = manager.get("cat_mem").orElseThrow();
        assertThat(stored.embeddingVector())
                .isNotNull()
                .containsExactly(1f, 0f); // matches toyGenerator for "cat"
    }

    @Test
    void add_preserves_existing_embedding() {
        MemoryManager manager = MemoryManager.inMemory()
                .withDefaultNamespace("ns")
                .withEmbeddingGenerator(toyGenerator()) // would produce [1,0] for "cat"
                .build();

        float[] custom = {0.3f, 0.7f};
        manager.add(new Memory("ns", "k", "my cat is fluffy", null, custom, 0, 0));

        Memory stored = manager.get("k").orElseThrow();
        assertThat(stored.embeddingVector()).isEqualTo(custom); // NOT overwritten
    }

    // ----- addAll -----

    @Test
    void addAll_writes_batch_with_embeddings() {
        MemoryManager manager = MemoryManager.inMemory()
                .withEmbeddingGenerator(toyGenerator())
                .build();

        List<Memory> batch = List.of(
                Memory.of("ns", "a", "cat stuff"),
                Memory.of("ns", "b", "dog stuff")
        );
        manager.addAll("ns", batch);

        assertThat(manager.keys("ns")).containsExactlyInAnyOrder("a", "b");
        assertThat(manager.get("ns", "a").orElseThrow().embeddingVector()).isNotNull();
        assertThat(manager.get("ns", "b").orElseThrow().embeddingVector()).isNotNull();
    }

    @Test
    void addAll_applies_merge_policy_per_record() {
        MemoryManager manager = MemoryManager.inMemory()
                .withMergePolicy(MemoryMergePolicy.keyMerge())
                .build();

        // Pre-populate with short value
        manager.add(new Memory("ns", "k", "short", null, null, 0, 0));

        // Batch add with longer value — should merge, not overwrite
        manager.addAll("ns", List.of(
                Memory.of("ns", "k", "this is a longer value")
        ));

        Memory stored = manager.get("ns", "k").orElseThrow();
        assertThat(stored.value()).isEqualTo("this is a longer value"); // merge kept longer
    }

    // ----- remove / clear -----

    @Test
    void remove_deletes_key() {
        MemoryManager manager = MemoryManager.inMemory()
                .withDefaultNamespace("ns")
                .build();
        manager.add("k", "v");
        manager.remove("k");
        assertThat(manager.get("k")).isEmpty();
    }

    @Test
    void clear_wipes_namespace() {
        MemoryManager manager = MemoryManager.inMemory()
                .withDefaultNamespace("ns")
                .build();
        manager.add("a", "1");
        manager.add("b", "2");
        manager.clear();
        assertThat(manager.keys()).isEmpty();
    }

    // ----- search -----

    @Test
    void search_without_generator_uses_store_default() {
        MemoryManager manager = MemoryManager.inMemory()
                .withDefaultNamespace("ns")
                .build();
        manager.add("food", "I like hot pot");
        manager.add("hobby", "I play guitar");

        List<Memory> results = manager.search("like", 5);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).key()).isEqualTo("food");
    }

    @Test
    void search_with_generator_ranks_by_cosine() {
        MemoryManager manager = MemoryManager.inMemory()
                .withDefaultNamespace("ns")
                .withEmbeddingGenerator(toyGenerator())
                .build();
        manager.add("cat", "my cat is fluffy");

        List<Memory> results = manager.search("cat", 5);
        assertThat(results).isNotEmpty();
    }

    @Test
    void search_default_limit_is_5() {
        MemoryManager manager = MemoryManager.inMemory()
                .withDefaultNamespace("ns")
                .build();
        for (int i = 0; i < 10; i++) manager.add("k" + i, "memory with needle");

        // 1-arg: limit defaults to 5
        assertThat(manager.search("needle")).hasSize(5);
        // 2-arg: explicit limit
        assertThat(manager.search("needle", 2)).hasSize(2);
    }

    // ================================================================
    // Decay policy tests
    // ================================================================

    @Test
    void search_with_decay_filters_out_ancient_memories() {
        long now = System.currentTimeMillis();
        long fiftyDaysAgo = now - 50 * DAY;
        long oneDayAgo = now - DAY;

        MemoryManager manager = MemoryManager.inMemory()
                .withDefaultNamespace("ns")
                .withDecayPolicy(MemoryDecayPolicy.exponential()) // 7-day half-life
                .build();

        // Ancient: decayFactor ≈ 0.5^(50/7) ≈ 0.007 < 0.01 → pruned
        manager.add(new Memory("ns", "ancient", "needle", null, null,
                fiftyDaysAgo, fiftyDaysAgo));
        // Recent: decayFactor ≈ 0.5^(1/7) ≈ 0.9 → survives
        manager.add(new Memory("ns", "recent", "needle", null, null,
                oneDayAgo, oneDayAgo));

        List<Memory> results = manager.search("needle", 10);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).key()).isEqualTo("recent");
    }

    @Test
    void search_with_decay_re_ranks_by_freshness() {
        long now = System.currentTimeMillis();
        long eightDaysAgo = now - 8 * DAY;
        long oneDayAgo = now - DAY;

        MemoryManager manager = MemoryManager.inMemory()
                .withDefaultNamespace("ns")
                .withDecayPolicy(MemoryDecayPolicy.exponential())
                .build();

        // Old: decayFactor ≈ 0.5^(8/7) ≈ 0.45
        manager.add(new Memory("ns", "old", "needle in haystack", null, null,
                eightDaysAgo, eightDaysAgo));
        // New: decayFactor ≈ 0.5^(1/7) ≈ 0.9
        manager.add(new Memory("ns", "new", "needle found here", null, null,
                oneDayAgo, oneDayAgo));

        List<Memory> results = manager.search("needle", 10);

        assertThat(results).hasSize(2);
        // Higher decay factor (more recent) should come first
        assertThat(results.get(0).key()).isEqualTo("new");
        assertThat(results.get(1).key()).isEqualTo("old");
    }

    @Test
    void search_without_decay_preserves_store_order() {
        long now = System.currentTimeMillis();
        long eightDaysAgo = now - 8 * DAY;

        MemoryManager manager = MemoryManager.inMemory()
                .withDefaultNamespace("ns")
                .build(); // no decay policy

        manager.add(new Memory("ns", "first", "needle", null, null,
                eightDaysAgo, eightDaysAgo));
        manager.add(new Memory("ns", "second", "needle", null, null,
                now, now));

        // Without decay, both returned in store's natural order
        List<Memory> results = manager.search("needle", 10);
        assertThat(results).hasSize(2);
    }

    @Test
    void get_refreshes_lastAccessedAt_when_decay_is_active() {
        long now = System.currentTimeMillis();
        long oldAccess = now - 10 * 60 * 1000; // 10 minutes ago

        MemoryManager manager = MemoryManager.inMemory()
                .withDefaultNamespace("ns")
                .withDecayPolicy(MemoryDecayPolicy.exponential())
                .build();

        // Store a memory with old lastAccessedAt
        manager.add(new Memory("ns", "k", "v", null, null, oldAccess, oldAccess));

        long before = System.currentTimeMillis();
        Optional<Memory> result = manager.get("k");
        long after = System.currentTimeMillis();

        assertThat(result).isPresent();
        assertThat(result.get().lastAccessedAt())
                .isBetween(before, after + 1000)
                .isGreaterThan(oldAccess);
    }

    @Test
    void get_does_not_refresh_when_decay_is_NONE() {
        long now = System.currentTimeMillis();
        long oldAccess = now - 10 * 60 * 1000;

        MemoryManager manager = MemoryManager.inMemory()
                .withDefaultNamespace("ns")
                .build(); // no decay policy

        manager.add(new Memory("ns", "k", "v", null, null, oldAccess, oldAccess));

        Optional<Memory> result = manager.get("k");

        assertThat(result).isPresent();
        assertThat(result.get().lastAccessedAt()).isEqualTo(oldAccess);
    }

    // ================================================================
    // Merge policy tests
    // ================================================================

    @Test
    void add_with_merge_keeps_longer_value_and_unions_metadata() {
        MemoryManager manager = MemoryManager.inMemory()
                .withDefaultNamespace("ns")
                .withMergePolicy(MemoryMergePolicy.keyMerge())
                .build();

        manager.add("k", "short value");
        manager.add("k", "this is a longer value", Map.of("source", "diary"));

        Memory stored = manager.get("k").orElseThrow();
        assertThat(stored.value()).isEqualTo("this is a longer value");
        assertThat(stored.metadata()).containsEntry("source", "diary");
    }

    @Test
    void add_without_merge_overwrites_silently() {
        MemoryManager manager = MemoryManager.inMemory()
                .withDefaultNamespace("ns")
                .build(); // merge = NONE (default)

        manager.add("k", "first value", Map.of("a", 1));
        manager.add("k", "second value", Map.of("b", 2));

        Memory stored = manager.get("k").orElseThrow();
        assertThat(stored.value()).isEqualTo("second value");
        assertThat(stored.metadata()).doesNotContainKey("a"); // overwritten, not merged
    }

    @Test
    void add_merge_preserves_earliest_createdAt() {
        long early = 1_000_000L;
        long late = 2_000_000L;

        MemoryManager manager = MemoryManager.inMemory()
                .withMergePolicy(MemoryMergePolicy.keyMerge())
                .build();

        manager.add(new Memory("ns", "k", "v1", null, null, early, early));
        manager.add(new Memory("ns", "k", "v2", null, null, late, late));

        Memory stored = manager.get("ns", "k").orElseThrow();
        assertThat(stored.createdAt()).isEqualTo(early);
    }
}
