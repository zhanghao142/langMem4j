package com.langmem4j.core.manager;

import com.langmem4j.core.memory.Memory;
import com.langmem4j.core.memory.MemoryCompactionPolicy;
import com.langmem4j.core.namespace.NamespaceResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the {@link NamespaceResolver} integration (dynamic-namespace
 * variant of every no-namespace manager call). The contract:
 * resolver result wins; null/blank falls back to defaultNamespace;
 * FIXED (default) keeps the pre-existing behavior.
 */
class MemoryManagerNamespaceResolverTest {

    // ── Resolver routes no-namespace calls ────────────────────

    @Test
    void resolver_result_routes_add_and_get() {
        MemoryManager manager = MemoryManager.inMemory()
                .withDefaultNamespace("default")
                .withNamespaceResolver(() -> "user_bob")
                .build();

        manager.add("food", "Bob likes sushi");

        // get() without namespace resolves to the same runtime namespace
        assertThat(manager.get("food")).hasValueSatisfying(m -> {
            assertThat(m.namespace()).isEqualTo("user_bob");
            assertThat(m.value()).isEqualTo("Bob likes sushi");
        });

        // ...and does NOT leak into the default namespace
        assertThat(manager.get("default", "food")).isEmpty();
    }

    @Test
    void resolver_applies_to_search_keys_remove_and_compact() {
        MemoryManager manager = MemoryManager.inMemory()
                .withDefaultNamespace("default")
                .withNamespaceResolver(() -> "user_alice")
                .withCompactionPolicy(MemoryCompactionPolicy.categoryGroup())
                .build();

        manager.add("a", "likes hot pot", Map.of("category", "food"));
        manager.add("a2", "likes sushi", Map.of("category", "food"));
        manager.add("b", "plays guitar", Map.of("category", "hobby"));
        manager.add("default", "c", "default-namespace item", null);

        assertThat(manager.keys()).containsExactlyInAnyOrder("a", "a2", "b");
        assertThat(manager.search("hot pot", 10))
                .extracting(Memory::key).containsExactly("a");

        manager.remove("b");
        assertThat(manager.keys()).containsExactlyInAnyOrder("a", "a2");

        manager.compact();   // resolves to user_alice, collapses to category summaries
        assertThat(manager.keys()).containsExactly("food_compacted");
        assertThat(manager.get("default", "c")).isPresent();   // default ns untouched
    }

    // ── Fallback semantics ────────────────────────────────────

    @Test
    void null_resolver_result_falls_back_to_default_namespace() {
        MemoryManager manager = MemoryManager.inMemory()
                .withDefaultNamespace("user_demo")
                .withNamespaceResolver(NamespaceResolver.FIXED)
                .build();

        manager.add("k", "v");
        assertThat(manager.get("k")).isPresent();
        assertThat(manager.get("user_demo", "k")).isPresent();
    }

    @Test
    void blank_resolver_result_falls_back_to_default_namespace() {
        MemoryManager manager = MemoryManager.inMemory()
                .withDefaultNamespace("user_demo")
                .withNamespaceResolver(() -> "   ")
                .build();

        manager.add("k", "v");
        assertThat(manager.get("user_demo", "k")).isPresent();
    }

    @Test
    void no_default_namespace_and_unresolving_context_still_fails_fast() {
        MemoryManager manager = MemoryManager.inMemory()
                .withNamespaceResolver(() -> "user_ok")   // works when context present
                .build();

        manager.add("k", "v");   // fine — resolved

        MemoryManager unresolved = MemoryManager.inMemory()
                .withNamespaceResolver(NamespaceResolver.FIXED)
                .build();
        assertThatThrownBy(() -> unresolved.add("k", "v"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("default namespace");
    }

    // ── Per-call resolution (context switches mid-flight) ─────

    @Test
    void resolver_is_consulted_per_call_so_context_switches_isolate_data() {
        AtomicReference<String> currentUser = new AtomicReference<>("alice");
        MemoryManager manager = MemoryManager.inMemory()
                .withDefaultNamespace("default")
                .withNamespaceResolver(() -> "user_" + currentUser.get())
                .build();

        manager.add("food", "Alice likes hot pot");
        currentUser.set("bob");
        manager.add("food", "Bob likes sushi");

        // Each caller only sees their own bucket — same key, different data
        currentUser.set("alice");
        assertThat(manager.get("food")).map(Memory::value).contains("Alice likes hot pot");
        currentUser.set("bob");
        assertThat(manager.get("food")).map(Memory::value).contains("Bob likes sushi");
    }

    // ── Explicit-namespace calls bypass the resolver ──────────

    @Test
    void explicit_namespace_argument_still_wins_over_resolver() {
        MemoryManager manager = MemoryManager.inMemory()
                .withDefaultNamespace("default")
                .withNamespaceResolver(() -> "user_alice")
                .build();

        manager.add("org_acme", "food", "explicit wins", null);

        assertThat(manager.get("org_acme", "food")).isPresent();
        assertThat(manager.get("food")).isEmpty();   // user_alice bucket untouched
        assertThat(List.copyOf(manager.keys("org_acme"))).containsExactly("food");
    }

    // ── Builder default keeps old behavior ────────────────────

    @Test
    void without_resolver_manager_uses_fixed_default_behavior() {
        MemoryManager manager = MemoryManager.inMemory()
                .withDefaultNamespace("ns")
                .build();

        assertThat(manager.namespaceResolver()).isSameAs(NamespaceResolver.FIXED);
        manager.add("k", "v");
        assertThat(manager.get("ns", "k")).isPresent();
    }
}
