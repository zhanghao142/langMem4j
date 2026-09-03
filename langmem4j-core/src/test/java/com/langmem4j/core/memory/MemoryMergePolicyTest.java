package com.langmem4j.core.memory;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryMergePolicyTest {

    // ----- NONE -----

    @Test
    void NONE_returns_incoming_unchanged() {
        Memory existing = Memory.of("ns", "k", "old value", Map.of("a", 1));
        Memory incoming = Memory.of("ns", "k", "new value", Map.of("b", 2));

        Memory result = MemoryMergePolicy.NONE.merge(existing, incoming);

        assertThat(result).isEqualTo(incoming);
    }

    // ----- keyMerge() — value -----

    @Test
    void keyMerge_keeps_longer_value() {
        Memory existing = Memory.of("ns", "k", "short");
        Memory incoming = Memory.of("ns", "k", "this is a longer value");

        Memory result = MemoryMergePolicy.keyMerge().merge(existing, incoming);

        assertThat(result.value()).isEqualTo("this is a longer value");
    }

    @Test
    void keyMerge_keeps_existing_when_incoming_is_shorter() {
        Memory existing = Memory.of("ns", "k", "this is a longer value");
        Memory incoming = Memory.of("ns", "k", "short");

        Memory result = MemoryMergePolicy.keyMerge().merge(existing, incoming);

        assertThat(result.value()).isEqualTo("this is a longer value");
    }

    // ----- keyMerge() — metadata -----

    @Test
    void keyMerge_unions_metadata_with_incoming_overrides() {
        Memory existing = Memory.of("ns", "k", "v", Map.of("a", 1, "b", 2));
        Memory incoming = Memory.of("ns", "k", "v", Map.of("b", 99, "c", 3));

        Memory result = MemoryMergePolicy.keyMerge().merge(existing, incoming);

        assertThat(result.metadata())
                .containsEntry("a", 1)    // from existing
                .containsEntry("b", 99)  // overridden by incoming
                .containsEntry("c", 3);  // from incoming
    }

    // ----- keyMerge() — timestamps -----

    @Test
    void keyMerge_preserves_earliest_createdAt() {
        long early = 1_000_000L;
        long late = 2_000_000L;
        Memory existing = new Memory("ns", "k", "v", null, null, early, early);
        Memory incoming = new Memory("ns", "k", "v", null, null, late, late);

        Memory result = MemoryMergePolicy.keyMerge().merge(existing, incoming);

        assertThat(result.createdAt()).isEqualTo(early);
    }

    @Test
    void keyMerge_preserves_earliest_createdAt_reversed() {
        long early = 1_000_000L;
        long late = 2_000_000L;
        Memory existing = new Memory("ns", "k", "v", null, null, late, late);
        Memory incoming = new Memory("ns", "k", "v", null, null, early, early);

        Memory result = MemoryMergePolicy.keyMerge().merge(existing, incoming);

        assertThat(result.createdAt()).isEqualTo(early);
    }

    @Test
    void keyMerge_sets_lastAccessed_to_approximately_now() {
        long before = System.currentTimeMillis();
        Memory existing = Memory.of("ns", "k", "v");
        Memory incoming = Memory.of("ns", "k", "v");
        // delay to ensure now != before
        long after = System.currentTimeMillis() + 1;

        Memory result = MemoryMergePolicy.keyMerge().merge(existing, incoming);

        assertThat(result.lastAccessedAt()).isBetween(before, after + 1000);
    }

    // ----- keyMerge() — embedding -----

    @Test
    void keyMerge_prefers_incoming_embedding() {
        float[] existingVec = {1f, 0f};
        float[] incomingVec = {0f, 1f};
        Memory existing = new Memory("ns", "k", "v", null, existingVec, 0, 0);
        Memory incoming = new Memory("ns", "k", "v", null, incomingVec, 0, 0);

        Memory result = MemoryMergePolicy.keyMerge().merge(existing, incoming);

        assertThat(result.embeddingVector()).containsExactly(0f, 1f);
    }

    @Test
    void keyMerge_falls_back_to_existing_when_incoming_has_none() {
        float[] existingVec = {1f, 0f};
        Memory existing = new Memory("ns", "k", "v", null, existingVec, 0, 0);
        Memory incoming = Memory.of("ns", "k", "v"); // no embedding

        Memory result = MemoryMergePolicy.keyMerge().merge(existing, incoming);

        assertThat(result.embeddingVector()).containsExactly(1f, 0f);
    }

    // ----- keyMerge() — immutability -----

    @Test
    void keyMerge_does_not_mutate_inputs() {
        Map<String, Object> existingMeta = new HashMap<>(Map.of("a", 1));
        Memory existing = Memory.of("ns", "k", "v", existingMeta);
        Memory incoming = Memory.of("ns", "k", "v", Map.of("b", 2));

        MemoryMergePolicy.keyMerge().merge(existing, incoming);

        // existing's metadata should still only have "a"
        assertThat(existing.metadata()).containsOnlyKeys("a");
        // existing's value should be unchanged
        assertThat(existing.value()).isEqualTo("v");
    }
}
