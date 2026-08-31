package com.langmem4j.store.qdrant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lightweight unit tests for {@link QdrantMemoryStore} helpers that do not
 * require a live Qdrant server. End-to-end tests that need a gRPC
 * connection live in {@link QdrantMemoryStoreIntegrationTest}, which is
 * {@code @Disabled} by default — enable it when a Qdrant container is
 * running on localhost:6334.
 */
class QdrantMemoryStoreTest {

    @Test
    void deterministicId_is_stable() {
        long id1 = QdrantMemoryStore.deterministicId("user1", "favorite_food");
        long id2 = QdrantMemoryStore.deterministicId("user1", "favorite_food");
        assertThat(id1).isEqualTo(id2);
    }

    @Test
    void deterministicId_differs_for_different_namespaces() {
        long id1 = QdrantMemoryStore.deterministicId("nsA", "key");
        long id2 = QdrantMemoryStore.deterministicId("nsB", "key");
        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    void deterministicId_differs_for_different_keys() {
        long id1 = QdrantMemoryStore.deterministicId("ns", "key1");
        long id2 = QdrantMemoryStore.deterministicId("ns", "key2");
        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    void deterministicId_is_positive() {
        // FNV-1a 64-bit, masked to positive long
        for (int i = 0; i < 100; i++) {
            long id = QdrantMemoryStore.deterministicId("ns" + i, "key" + i);
            assertThat(id).isGreaterThan(0);
        }
    }
}
