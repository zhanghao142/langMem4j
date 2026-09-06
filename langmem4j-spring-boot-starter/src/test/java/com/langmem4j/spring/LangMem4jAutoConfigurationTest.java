package com.langmem4j.spring;

import com.langmem4j.core.manager.MemoryManager;
import com.langmem4j.core.memory.MemoryDecayPolicy;
import com.langmem4j.core.memory.MemoryMergePolicy;
import com.langmem4j.core.memory.MemoryCompactionPolicy;
import com.langmem4j.core.namespace.NamespaceResolver;
import com.langmem4j.core.store.MemoryStore;
import com.langmem4j.core.store.inmemory.InMemoryMemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ApplicationContextRunner-based unit tests — no full Spring Boot startup,
 * runs in milliseconds. Verifies the auto-configuration contract:
 * defaults, property-driven switches, and user-bean precedence.
 */
class LangMem4jAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LangMem4jAutoConfiguration.class));

    // ── Defaults ──────────────────────────────────────────────

    @Test
    void default_config_loads_inmemory_store_and_manager() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(MemoryStore.class);
            assertThat(context).hasSingleBean(InMemoryMemoryStore.class);
            assertThat(context).hasSingleBean(MemoryManager.class);
            assertThat(context.getBean(MemoryStore.class)).isInstanceOf(InMemoryMemoryStore.class);
            // Default namespace
            assertThat(context.getBean(MemoryManager.class).defaultNamespace()).contains("default");
        });
    }

    @Test
    void decay_merge_compaction_disabled_by_default() {
        runner.run(context -> {
            MemoryManager manager = context.getBean(MemoryManager.class);
            assertThat(manager.decayPolicy()).isSameAs(MemoryDecayPolicy.NONE);
            assertThat(manager.mergePolicy()).isSameAs(MemoryMergePolicy.NONE);
            assertThat(manager.compactionPolicy()).isSameAs(MemoryCompactionPolicy.NONE);
        });
    }

    // ── Property switches ─────────────────────────────────────

    @Test
    void decay_enabled_wires_exponential_policy_with_threshold() {
        runner.withPropertyValues(
                        "langmem4j.decay.enabled=true",
                        "langmem4j.decay.half-life=PT2H",
                        "langmem4j.decay.prune-threshold=0.001")
                .run(context -> {
                    MemoryManager manager = context.getBean(MemoryManager.class);
                    assertThat(manager.decayPolicy()).isNotSameAs(MemoryDecayPolicy.NONE);
                    // Custom prune threshold from properties
                    assertThat(manager.decayPolicy().pruneThreshold()).isEqualTo(0.001f);
                    // Half-life 2h: 2h-old memory → factor ≈ 0.5
                    long now = System.currentTimeMillis();
                    float factor = manager.decayPolicy().decayFactor(now, now - 2 * 60 * 60 * 1000L, now);
                    assertThat(factor).isBetween(0.45f, 0.55f);
                });
    }

    @Test
    void merge_enabled_wires_keyMerge() {
        runner.withPropertyValues("langmem4j.merge.enabled=true")
                .run(context -> {
                    MemoryManager manager = context.getBean(MemoryManager.class);
                    assertThat(manager.mergePolicy()).isNotSameAs(MemoryMergePolicy.NONE);

                    // Functional check: two adds on the same key merge into one
                    manager.add("food", "Alice likes hot pot", Map.of("src", "user"));
                    manager.add("food", "Alice likes spicy hot pot with sesame", Map.of("src", "diary"));
                    var stored = manager.get("food").orElseThrow();
                    assertThat(stored.value()).isEqualTo("Alice likes spicy hot pot with sesame");
                });
    }

    @Test
    void compaction_enabled_wires_categoryGroup() {
        runner.withPropertyValues("langmem4j.compaction.enabled=true")
                .run(context -> {
                    MemoryManager manager = context.getBean(MemoryManager.class);
                    assertThat(manager.compactionPolicy()).isNotSameAs(MemoryCompactionPolicy.NONE);
                });
    }

    @Test
    void custom_namespace_property_is_applied() {
        runner.withPropertyValues("langmem4j.default-namespace=user_alice")
                .run(context -> assertThat(context.getBean(MemoryManager.class).defaultNamespace())
                        .contains("user_alice"));
    }

    // ── Namespace pattern (runtime resolution) ─────────────────

    @Test
    void namespace_pattern_creates_resolver_wired_into_manager() {
        runner.withPropertyValues("langmem4j.namespace-pattern=user_#{#principal}")
                .run(context -> {
                    assertThat(context).hasSingleBean(NamespaceResolver.class);
                    assertThat(context.getBean(NamespaceResolver.class))
                            .isInstanceOf(PatternNamespaceResolver.class);
                    // The manager actually uses the resolver bean
                    assertThat(context.getBean(MemoryManager.class).namespaceResolver())
                            .isSameAs(context.getBean(NamespaceResolver.class));
                });
    }

    @Test
    void no_pattern_by_default_means_no_resolver_bean() {
        runner.run(context ->
                assertThat(context).doesNotHaveBean(NamespaceResolver.class));
    }

    @Test
    void resolver_reads_principal_from_security_context() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", "n/a"));
        try {
            runner.withPropertyValues("langmem4j.namespace-pattern=user_#{#principal}")
                    .run(context -> {
                        NamespaceResolver resolver = context.getBean(NamespaceResolver.class);
                        assertThat(resolver.resolve()).isEqualTo("user_alice");

                        // End-to-end: the manager routes by resolved namespace
                        MemoryManager manager = context.getBean(MemoryManager.class);
                        manager.add("food", "Alice likes hot pot");
                        assertThat(manager.get("user_alice", "food")).isPresent();
                        assertThat(manager.get("default", "food")).isEmpty();
                    });
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void unauthenticated_request_falls_back_to_default_namespace() {
        SecurityContextHolder.clearContext();
        runner.withPropertyValues("langmem4j.namespace-pattern=user_#{#principal}")
                .run(context -> {
                    NamespaceResolver resolver = context.getBean(NamespaceResolver.class);
                    // No principal in context → null → manager default namespace
                    assertThat(resolver.resolve()).isNull();
                });
    }

    @Test
    void custom_resolver_bean_wins_over_pattern() {
        runner.withUserConfiguration(CustomResolverConfig.class)
                .withPropertyValues("langmem4j.namespace-pattern=user_#{#principal}")
                .run(context -> {
                    assertThat(context).hasSingleBean(NamespaceResolver.class);
                    assertThat(context.getBean(NamespaceResolver.class))
                            .isSameAs(CustomResolverConfig.CUSTOM_RESOLVER);
                });
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ── User beans always win ─────────────────────────────────

    @Test
    void custom_store_bean_wins_over_autoconfig() {
        runner.withUserConfiguration(CustomStoreConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(MemoryStore.class);
                    assertThat(context.getBean(MemoryStore.class))
                            .isSameAs(CustomStoreConfig.CUSTOM_STORE);
                });
    }

    @Test
    void custom_manager_bean_wins_over_autoconfig() {
        runner.withUserConfiguration(CustomManagerConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(MemoryManager.class);
                    assertThat(context.getBean(MemoryManager.class))
                            .isSameAs(CustomManagerConfig.CUSTOM_MANAGER);
                });
    }

    // ── Failure modes ─────────────────────────────────────────

    @Test
    void unknown_store_type_fails_startup() {
        runner.withPropertyValues("langmem4j.store.type=bogus")
                .run(context -> {
                    // Neither inmemory nor qdrant bean matches → MemoryManager
                    // cannot be created (no MemoryStore bean)
                    assertThat(context).hasFailed();
                });
    }

    @Test
    void compaction_llm_policy_not_yet_supported_fails_fast() {
        runner.withPropertyValues(
                        "langmem4j.compaction.enabled=true",
                        "langmem4j.compaction.policy=llm")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("langmem4j-strategy");
                });
    }

    // ── Test fixtures ─────────────────────────────────────────

    @Configuration(proxyBeanMethods = false)
    static class CustomStoreConfig {
        static final InMemoryMemoryStore CUSTOM_STORE = new InMemoryMemoryStore();

        @Bean
        MemoryStore customStore() {
            return CUSTOM_STORE;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomManagerConfig {
        static final MemoryManager CUSTOM_MANAGER = MemoryManager.inMemory()
                .withDefaultNamespace("user-defined")
                .build();

        @Bean
        MemoryManager customManager() {
            return CUSTOM_MANAGER;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomResolverConfig {
        static final NamespaceResolver CUSTOM_RESOLVER = () -> "user_custom";

        @Bean
        NamespaceResolver customResolver() {
            return CUSTOM_RESOLVER;
        }
    }
}
