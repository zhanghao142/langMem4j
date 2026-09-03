package com.langmem4j.core.memory;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryTest {

    @Test
    void constructor_rejects_null_namespace() {
        assertThatThrownBy(() -> Memory.of(null, "k", "v"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("namespace");
    }

    @Test
    void constructor_rejects_blank_key() {
        assertThatThrownBy(() -> Memory.of("ns", "   ", "v"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key");
    }

    @Test
    void constructor_rejects_null_value() {
        assertThatThrownBy(() -> Memory.of("ns", "k", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("value");
    }

    @Test
    void metadata_is_defensively_copied_and_unmodifiable() {
        Map<String, Object> original = new HashMap<>();
        original.put("author", "alice");

        Memory memory = Memory.of("ns", "k", "v", original);
        original.put("author", "bob"); // mutate the source

        // memory metadata should still be "alice"
        assertThat(memory.metadata()).containsEntry("author", "alice");

        // memory metadata should be unmodifiable
        assertThatThrownBy(() -> memory.metadata().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void embedding_vector_is_defensively_copied() {
        float[] vector = {1f, 2f, 3f};
        Memory memory = new Memory("ns", "k", "v", null, vector, 0, 0);
        vector[0] = 999f; // mutate the source

        assertThat(memory.embeddingVector()).containsExactly(1f, 2f, 3f);
    }

    @Test
    void equals_and_hashCode_handle_vector_by_content() {
        Memory m1 = new Memory("ns", "k", "v", null, new float[]{0.1f, 0.2f}, 0, 0);
        Memory m2 = new Memory("ns", "k", "v", null, new float[]{0.1f, 0.2f}, 0, 0);
        Memory m3 = new Memory("ns", "k", "v", null, new float[]{0.1f, 0.9f}, 0, 0);

        assertThat(m1).isEqualTo(m2);
        assertThat(m1).isNotEqualTo(m3);
        assertThat(m1.hashCode()).isEqualTo(m2.hashCode());
    }

    @Test
    void withEmbedding_returns_new_instance() {
        Memory original = Memory.of("ns", "k", "v");
        float[] newVector = {7f, 8f};

        Memory updated = original.withEmbedding(newVector);

        assertThat(original.embeddingVector()).isNull();
        assertThat(updated.embeddingVector()).containsExactly(7f, 8f);
        assertThat(updated).isNotSameAs(original);
    }

    @Test
    void withMetadata_merges_extra_tags() {
        Memory base = Memory.of("ns", "k", "v", Map.of("a", 1));

        Memory merged = base.withMetadata(Map.of("b", 2, "a", 99));

        assertThat(merged.metadata())
                .containsEntry("a", 99) // overridden
                .containsEntry("b", 2);  // added
        // original untouched
        assertThat(base.metadata()).containsEntry("a", 1);
        assertThat(base.metadata()).doesNotContainKey("b");
    }

    @Test
    void withMetadata_empty_or_null_is_noop() {
        Memory base = Memory.of("ns", "k", "v", Map.of("a", 1));

        assertThat(base.withMetadata(null)).isSameAs(base);
        assertThat(base.withMetadata(Map.of())).isSameAs(base);
    }

    @Test
    void factory_methods_produce_equivalent_memories() {
        Memory viaFactory = Memory.of("ns", "k", "v");
        Memory direct = new Memory("ns", "k", "v", null, null, 0, 0);

        assertThat(viaFactory).isEqualTo(direct);
        assertThat(direct.metadata()).isEmpty();
        assertThat(viaFactory.embeddingVector()).isNull();
    }
}
