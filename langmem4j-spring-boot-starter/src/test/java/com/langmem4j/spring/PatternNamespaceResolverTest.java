package com.langmem4j.spring;

import org.junit.jupiter.api.Test;
import org.springframework.expression.ParseException;
import org.springframework.expression.spel.SpelEvaluationException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the SpEL-template namespace resolver: template rendering,
 * fallback semantics (null → default namespace), the SimpleEvaluationContext
 * security whitelist, and result-cache behaviour.
 */
class PatternNamespaceResolverTest {

    private static NamespaceVariables vars(String name, Object value) {
        Map<String, Object> map = new HashMap<>();
        map.put(name, value);
        return () -> map;
    }

    // ── Template rendering ─────────────────────────────────────

    @Test
    void principal_template_renders_with_prefix() {
        PatternNamespaceResolver resolver = new PatternNamespaceResolver(
                "user_#{#principal}", List.of(vars("principal", "alice")), null);

        assertThat(resolver.resolve()).isEqualTo("user_alice");
    }

    @Test
    void static_template_without_expressions_is_returned_as_is() {
        PatternNamespaceResolver resolver = new PatternNamespaceResolver(
                "shared", List.of(), null);

        assertThat(resolver.resolve()).isEqualTo("shared");
    }

    @Test
    void header_variable_supports_map_indexing_and_elvis() {
        PatternNamespaceResolver resolver = new PatternNamespaceResolver(
                "tenant_#{#header['X-Tenant-Id'] ?: 'anonymous'}",
                List.of(vars("header", Map.of())), null);

        // Header absent → elvis default
        assertThat(resolver.resolve()).isEqualTo("tenant_anonymous");
    }

    @Test
    void header_present_indexing_reads_value() {
        PatternNamespaceResolver resolver = new PatternNamespaceResolver(
                "#{#header['X-Tenant-Id']}",
                List.of(vars("header", Map.of("X-Tenant-Id", "acme"))), null);

        assertThat(resolver.resolve()).isEqualTo("acme");
    }

    // ── Fallback semantics (null → manager's default namespace) ─

    @Test
    void missing_referenced_variable_returns_null() {
        // No providers at all → #principal unavailable → fall back
        PatternNamespaceResolver resolver = new PatternNamespaceResolver(
                "user_#{#principal}", List.of(), null);

        assertThat(resolver.resolve()).isNull();
    }

    @Test
    void blank_render_result_returns_null() {
        PatternNamespaceResolver resolver = new PatternNamespaceResolver(
                "#{#principal}", List.of(vars("principal", "")), null);

        assertThat(resolver.resolve()).isNull();
    }

    // ── Security: SimpleEvaluationContext whitelist ────────────

    @Test
    void type_references_are_blocked() {
        PatternNamespaceResolver resolver = new PatternNamespaceResolver(
                "#{T(java.lang.System).getenv('PATH')}", List.of(), null);

        // SimpleEvaluationContext refuses T(...) — fails loudly at resolve
        assertThatThrownBy(resolver::resolve)
                .isInstanceOf(SpelEvaluationException.class);
    }

    @Test
    void constructors_are_blocked() {
        PatternNamespaceResolver resolver = new PatternNamespaceResolver(
                "#{new java.io.File('/tmp').absolute}", List.of(), null);

        assertThatThrownBy(resolver::resolve)
                .isInstanceOf(SpelEvaluationException.class);
    }

    // ── Construction validation ────────────────────────────────

    @Test
    void invalid_template_fails_at_construction() {
        assertThatThrownBy(() -> new PatternNamespaceResolver(
                "user_#{#principal", List.of(), null))
                .isInstanceOf(ParseException.class);
    }

    @Test
    void blank_pattern_is_rejected() {
        assertThatThrownBy(() -> new PatternNamespaceResolver(
                "  ", List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Result cache ───────────────────────────────────────────

    @Test
    void principal_only_patterns_are_cached_per_principal() {
        AtomicReference<String> principal = new AtomicReference<>("alice");
        PatternNamespaceResolver resolver = new PatternNamespaceResolver(
                "user_#{#principal}",
                List.of(() -> Map.of("principal", principal.get())),
                new NamespaceResultCache(100, 60_000));

        assertThat(resolver.resolve()).isEqualTo("user_alice");
        assertThat(resolver.resolve()).isEqualTo("user_alice");
        // Same principal twice → one SpEL evaluation, the second was a hit
        assertThat(resolver.evaluations()).isEqualTo(1);

        // Switching the principal changes the cache key → re-evaluated
        principal.set("bob");
        assertThat(resolver.resolve()).isEqualTo("user_bob");
        assertThat(resolver.evaluations()).isEqualTo(2);
    }

    @Test
    void header_patterns_are_never_cached() {
        PatternNamespaceResolver resolver = new PatternNamespaceResolver(
                "#{#header['X-User-Id'] ?: 'anonymous'}",
                List.of(vars("header", Map.of())),
                new NamespaceResultCache(100, 60_000));

        resolver.resolve();
        resolver.resolve();
        // Header-dependent results cannot be keyed safely → always evaluated
        assertThat(resolver.evaluations()).isEqualTo(2);
    }

    @Test
    void cache_lru_eviction_is_bounded() {
        NamespaceResultCache cache = new NamespaceResultCache(2, 60_000);
        cache.put("p=a", "user_a");
        cache.put("p=b", "user_b");
        cache.put("p=c", "user_c");

        assertThat(cache.size()).isEqualTo(2);
        // "p=a" was evicted (LRU)
        assertThat(cache.get("p=a")).isNull();
        assertThat(cache.get("p=b")).isEqualTo("user_b");
    }
}
