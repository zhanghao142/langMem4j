package com.langmem4j.core.store.inmemory;

import com.langmem4j.core.embedding.EmbeddingGenerator;
import com.langmem4j.core.memory.Memory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CosineSearchTest {

    /**
     * Simple 2D embedding generator: encodes text as [1, 0] if it contains
     * "cat", [0, 1] if it contains "dog", otherwise [0.5, 0.5]. This gives
     * us three distinguishable "clusters" to verify cosine ranking.
     */
    private static EmbeddingGenerator toyGenerator() {
        return text -> {
            String t = text.toLowerCase();
            if (t.contains("cat")) return new float[]{1f, 0f};
            if (t.contains("dog")) return new float[]{0f, 1f};
            return new float[]{0.5f, 0.5f};
        };
    }

    @Test
    void cosine_identical_vectors_score_one() {
        float[] a = {1f, 0f};
        float[] b = {1f, 0f};
        assertThat(InMemoryMemoryStore.cosine(a, b)).isEqualTo(1.0f);
    }

    @Test
    void cosine_orthogonal_vectors_score_zero() {
        float[] a = {1f, 0f};
        float[] b = {0f, 1f};
        assertThat(InMemoryMemoryStore.cosine(a, b)).isEqualTo(0.0f);
    }

    @Test
    void cosine_opposite_vectors_score_negative_one() {
        float[] a = {1f, 0f};
        float[] b = {-1f, 0f};
        assertThat(InMemoryMemoryStore.cosine(a, b)).isEqualTo(-1.0f);
    }

    @Test
    void cosine_zero_norm_returns_nan() {
        float[] zero = {0f, 0f};
        assertThat(Float.isNaN(InMemoryMemoryStore.cosine(zero, zero))).isTrue();
    }

    @Test
    void cosine_requires_equal_length() {
        assertThatThrownBy(() -> InMemoryMemoryStore.cosine(new float[]{1f}, new float[]{1f, 2f}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void semantic_search_ranks_by_cosine_similarity() {
        InMemoryMemoryStore store = new InMemoryMemoryStore(toyGenerator());

        store.upsert("ns", new Memory("ns", "m_cat", "my cat is fluffy",
                null, new float[]{1f, 0f}));
        store.upsert("ns", new Memory("ns", "m_dog", "my dog barks loud",
                null, new float[]{0f, 1f}));
        store.upsert("ns", new Memory("ns", "m_neutral", "the weather is nice",
                null, new float[]{0.5f, 0.5f}));

        List<Memory> results = store.search("ns", "cat", 3);

        assertThat(results).hasSize(3);
        // "cat" query vector [1,0] is closest to cat memory, then neutral, then dog
        assertThat(results.get(0).key()).isEqualTo("m_cat");
        assertThat(results.get(1).key()).isEqualTo("m_neutral");
        assertThat(results.get(2).key()).isEqualTo("m_dog");
    }

    @Test
    void semantic_search_skips_memories_without_embedding() {
        InMemoryMemoryStore store = new InMemoryMemoryStore(toyGenerator());

        // stored with embedding
        store.upsert("ns", new Memory("ns", "with_emb", "cat",
                null, new float[]{1f, 0f}));
        // stored WITHOUT embedding — should be skipped
        store.upsert("ns", Memory.of("ns", "no_emb", "cat"));

        List<Memory> results = store.search("ns", "cat", 5);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).key()).isEqualTo("with_emb");
    }
}
