package com.langmem4j.core.store.inmemory;

import com.langmem4j.core.memory.Memory;
import com.langmem4j.core.store.MemoryFilter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryMemoryStoreFilterTest {

    // ----- substring path with filter -----

    @Test
    void search_substring_with_metadata_filter_restricts_candidates() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        store.upsert("ns",
                Memory.of("ns", "a", "hot pot and rice", Map.of("kind", "food")));
        store.upsert("ns",
                Memory.of("ns", "b", "rice field painting", Map.of("kind", "art")));
        store.upsert("ns",
                Memory.of("ns", "c", "fried rice dish", Map.of("kind", "food")));

        // Unfiltered returns all 3 matches
        assertThat(store.search("ns", "rice", 10)).hasSize(3);

        // Filtered to kind=food returns only a + c, ranked by encounter order
        List<Memory> results = store.search("ns", "rice", 10,
                MemoryFilter.builder().metadata("kind", "food").build());
        assertThat(results).extracting(Memory::key).containsExactlyInAnyOrder("a", "c");
    }

    @Test
    void search_substring_filter_returns_empty_when_nothing_matches() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        store.upsert("ns", Memory.of("ns", "a", "potato", Map.of("kind", "food")));

        List<Memory> results = store.search("ns", "potato", 10,
                MemoryFilter.builder().metadata("kind", "vehicles").build());
        assertThat(results).isEmpty();
    }

    // ----- cosine path with filter & minScore -----

    static float[] toy(String text) {
        String t = text.toLowerCase();
        if (t.contains("cat"))    return new float[]{1f, 0f, 0f};
        if (t.contains("dog"))    return new float[]{0f, 1f, 0f};
        if (t.contains("car"))    return new float[]{0f, 0f, 1f};
        return new float[]{0.3f, 0.3f, 0.3f};
    }

    @Test
    void search_cosine_with_metadata_filter_restricts_candidates() {
        InMemoryMemoryStore store = new InMemoryMemoryStore(
                InMemoryMemoryStoreFilterTest::toy);
        Memory a = new Memory("ns", "cat-food",  "the cat eats tuna",
                Map.of("topic", "pet"), new float[]{1f, 0f, 0f});
        Memory b = new Memory("ns", "car-show",  "the cat C7 won a prize",
                Map.of("topic", "car"), new float[]{0f, 0f, 1f});
        store.upsert("ns", a);
        store.upsert("ns", b);

        // Unfiltered search for "cat" returns cat-food first, then car-show
        assertThat(store.search("ns", "cat", 10))
                .extracting(Memory::key)
                .containsExactly("cat-food", "car-show");

        // Filtered to topic=car only returns car-show despite lower cosine
        assertThat(store.search("ns", "cat", 10,
                MemoryFilter.builder().metadata("topic", "car").build()))
                .extracting(Memory::key)
                .containsExactly("car-show");
    }

    @Test
    void search_cosine_with_minScore_drops_below_threshold() {
        InMemoryMemoryStore store = new InMemoryMemoryStore(
                InMemoryMemoryStoreFilterTest::toy);
        store.upsert("ns", new Memory("ns", "a", "my cat is here",
                Map.of(), new float[]{1f, 0f, 0f}));
        store.upsert("ns", new Memory("ns", "b", "my dog is here",
                Map.of(), new float[]{0f, 1f, 0f}));

        // query = "cat" produces [1,0,0]. cos with dog = 0.0.
        // with minScore 0.5 → only cat
        List<Memory> r = store.search("ns", "cat", 10,
                MemoryFilter.builder().minScore(0.5f).build());
        assertThat(r).extracting(Memory::key).containsExactly("a");
    }

    // ----- NONE behaves exactly like 3-arg search -----

    @Test
    void NONE_filter_equals_three_arg() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        store.upsert("ns", Memory.of("ns", "a", "needle in haystack"));
        store.upsert("ns", Memory.of("ns", "b", "no match"));

        List<Memory> three = store.search("ns", "needle", 10);
        List<Memory> four = store.search("ns", "needle", 10, MemoryFilter.NONE);
        List<Memory> nullF = store.search("ns", "needle", 10, null);
        assertThat(three).isEqualTo(four);
        assertThat(four).isEqualTo(nullF);
    }
}
