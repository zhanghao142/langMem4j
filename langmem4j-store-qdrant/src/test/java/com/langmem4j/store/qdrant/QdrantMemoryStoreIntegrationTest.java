package com.langmem4j.store.qdrant;

import com.langmem4j.core.embedding.EmbeddingGenerator;
import com.langmem4j.core.memory.Memory;
import com.langmem4j.core.store.MemoryFilter;
import com.langmem4j.core.store.MemoryStore;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests for {@link QdrantMemoryStore} against a real Qdrant
 * instance.
 *
 * <h3>How to enable</h3>
 * <ol>
 *   <li>Start a Qdrant container:
 *   <pre>{@code
 *   docker run -d --rm --name qdrant-langmem4j-test \
 *       -p 6333:6333 -p 6334:6334 qdrant/qdrant
 *   }</pre></li>
 *   <li>Remove the {@code @Disabled} annotation from this class (or set
 *   the JVM flag {@code -Dmaven.test.skip=false} / run with
 *   {@code -Dtest=QdrantMemoryStoreIntegrationTest
 *   -Dsurefire.failIfNoSpecifiedTests=false} when the annotation is gone).</li>
 *   <li>{@code mvn test -pl langmem4j-store-qdrant -am}.</li>
 * </ol>
 * The class uses a dedicated test collection name {@code langmem4j_it_*}
 * and cleans it up in {@link #tearDown()} so a crashed run doesn't leave
 * state behind.
 *
 * @see QdrantMemoryStoreTest for pure-unit (no server) tests
 */
@Disabled("Enable manually when a Qdrant instance is available on localhost:6334")
class QdrantMemoryStoreIntegrationTest {

    private static final String HOST = "localhost";
    private static final int    PORT = 6334;           // gRPC
    private static final String NS   = "langmem4j_it_user";
    private static final int    DIM  = 3;

    private QdrantClient client;
    private MemoryStore store;

    /** 3D toy generator for deterministic smoke assertions. */
    private static final EmbeddingGenerator TOY = text -> {
        String t = text.toLowerCase();
        if (t.contains("apple"))  return new float[]{1f, 0f, 0f};
        if (t.contains("banana")) return new float[]{0f, 1f, 0f};
        return new float[]{0f, 0f, 1f};
    };

    @BeforeEach
    void setUp() {
        client = new QdrantClient(QdrantGrpcClient.newBuilder(HOST, PORT, false).build());
        store = new QdrantMemoryStore(client, TOY, DIM);
        store.clearNamespace(NS); // ensure clean slate
    }

    @AfterEach
    void tearDown() {
        try {
            store.clearNamespace(NS);
        } finally {
            if (client != null) {
                try { client.close(); } catch (Exception ignored) { /* best effort */ }
            }
        }
    }

    @Test
    void upsert_get_round_trip() {
        Memory m = Memory.of(NS, "fruit", "I like apple", Map.of("color", "red"));
        store.upsert(NS, m);

        Memory got = store.getByKey(NS, "fruit").orElseThrow();
        assertThat(got.value()).isEqualTo("I like apple");
        assertThat(got.metadata()).containsEntry("color", "red");
    }

    @Test
    void search_returns_cosine_ranked_results() {
        store.upsert(NS, Memory.of(NS, "a", "apple is sweet", Map.of("category", "fruit")));
        store.upsert(NS, Memory.of(NS, "b", "banana is yellow", Map.of("category", "fruit")));
        store.upsert(NS, Memory.of(NS, "c", "the sky is blue", Map.of("category", "misc")));

        List<Memory> results = store.search(NS, "apple", 3);
        assertThat(results).isNotEmpty();
        assertThat(results.get(0).key()).isEqualTo("a");
    }

    @Test
    void search_with_memoryFilter_restricts_to_metadata() {
        store.upsert(NS, Memory.of(NS, "a", "apple juice",    Map.of("kind", "drink")));
        store.upsert(NS, Memory.of(NS, "b", "apple fruit",    Map.of("kind", "solid")));

        List<Memory> onlyDrinks = store.search(NS, "apple", 10,
                MemoryFilter.builder().metadata("kind", "drink").build());

        assertThat(onlyDrinks).hasSize(1);
        assertThat(onlyDrinks.get(0).key()).isEqualTo("a");
    }

    @Test
    void delete_and_list_keys() {
        store.upsert(NS, Memory.of(NS, "k1", "v1"));
        store.upsert(NS, Memory.of(NS, "k2", "v2"));

        assertThat(store.listKeys(NS)).containsExactlyInAnyOrder("k1", "k2");
        store.deleteByKey(NS, "k1");
        assertThat(store.listKeys(NS)).containsExactly("k2");
    }
}
