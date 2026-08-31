package com.langmem4j.core.store;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryFilterTest {

    // ----- construction / identity -----

    @Test
    void NONE_has_no_constraints() {
        MemoryFilter f = MemoryFilter.NONE;
        assertThat(f.hasMetadataMatch()).isFalse();
        assertThat(f.hasMinScore()).isFalse();
        assertThat(f.metadataMatch()).isEmpty();
    }

    @Test
    void builder_empty_equals_NONE() {
        assertThat(MemoryFilter.builder().build()).isSameAs(MemoryFilter.NONE);
    }

    @Test
    void metadata_of_emptyMap_returns_NONE() {
        assertThat(MemoryFilter.metadata(Map.of())).isSameAs(MemoryFilter.NONE);
        assertThat(MemoryFilter.metadata(null)).isSameAs(MemoryFilter.NONE);
    }

    // ----- metadata match semantics -----

    @Test
    void matchesMetadata_accepts_when_all_required_keys_equal() {
        MemoryFilter f = MemoryFilter.builder()
                .metadata("category", "food")
                .metadata("source", "user")
                .build();
        assertThat(f.matchesMetadata(Map.of(
                "category", "food",
                "source",   "user",
                "extra",    "ignored"))).isTrue();
    }

    @Test
    void matchesMetadata_rejects_when_value_mismatches() {
        MemoryFilter f = MemoryFilter.builder()
                .metadata("category", "food").build();
        assertThat(f.matchesMetadata(Map.of("category", "hobby"))).isFalse();
    }

    @Test
    void matchesMetadata_rejects_when_key_missing() {
        MemoryFilter f = MemoryFilter.builder()
                .metadata("category", "food").build();
        assertThat(f.matchesMetadata(Map.of("other", "x"))).isFalse();
        assertThat(f.matchesMetadata(null)).isFalse();
    }

    @Test
    void matchesMetadata_null_required_value_means_missing_or_null() {
        MemoryFilter f = MemoryFilter.builder()
                .metadata("confidential", null) // must be absent or null
                .build();
        // Absent → OK
        assertThat(f.matchesMetadata(Map.of("a", "b"))).isTrue();
        // Null → OK (use mutable HashMap because Map.of() rejects null values)
        var withNull = new java.util.HashMap<String, Object>();
        withNull.put("confidential", null);
        assertThat(f.matchesMetadata(withNull)).isTrue();
        // Present → reject
        assertThat(f.matchesMetadata(Map.of("confidential", true))).isFalse();
    }

    @Test
    void numeric_and_boolean_compared_by_equals() {
        MemoryFilter f = MemoryFilter.builder()
                .metadata("age", 30)
                .metadata("active", true)
                .build();
        assertThat(f.matchesMetadata(Map.of("age", 30, "active", true))).isTrue();
        // integer vs long: 30 != 30L
        assertThat(f.matchesMetadata(Map.of("age", 30L, "active", true))).isFalse();
    }

    // ----- minScore -----

    @Test
    void builder_minScore_recorded() {
        MemoryFilter f = MemoryFilter.builder().minScore(0.8f).build();
        assertThat(f.hasMinScore()).isTrue();
        assertThat(f.minScore()).isEqualTo(0.8f);
    }

    // ----- equals / hashCode / toString -----

    @Test
    void equals_and_hashCode_structural() {
        MemoryFilter a = MemoryFilter.builder()
                .metadata("k", "v").minScore(0.5f).build();
        MemoryFilter b = MemoryFilter.builder()
                .metadata(Map.of("k", "v")).minScore(0.5f).build();
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void toString_mentions_metadata_and_NaN_as_N_A() {
        String s = MemoryFilter.builder().metadata("a", 1).build().toString();
        assertThat(s).contains("a").doesNotContain("NaN");

        String s2 = MemoryFilter.NONE.toString();
        assertThat(s2).contains("N/A");
    }
}
