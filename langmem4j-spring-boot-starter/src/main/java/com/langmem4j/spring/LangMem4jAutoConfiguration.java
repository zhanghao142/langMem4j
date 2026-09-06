package com.langmem4j.spring;

import com.langmem4j.core.embedding.EmbeddingGenerator;
import com.langmem4j.core.manager.MemoryManager;
import com.langmem4j.core.memory.MemoryCompactionPolicy;
import com.langmem4j.core.memory.MemoryDecayPolicy;
import com.langmem4j.core.memory.MemoryMergePolicy;
import com.langmem4j.core.namespace.NamespaceResolver;
import com.langmem4j.core.store.MemoryStore;
import com.langmem4j.core.store.inmemory.InMemoryMemoryStore;
import io.qdrant.client.QdrantClient;
import com.langmem4j.store.qdrant.QdrantMemoryStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Spring Boot auto-configuration for langMem4j.
 * <p>
 * Wires a {@link MemoryManager} bean from {@code langmem4j.*} properties:
 * storage backend ({@code inmemory} default, {@code qdrant} optional),
 * decay / merge / compaction policies, and an optional
 * {@link EmbeddingGenerator} bean if the application defines one.
 * <p>
 * All beans are {@code @ConditionalOnMissingBean} — application-defined
 * beans always win, so the starter never gets in your way.
 *
 * <pre>{@code
 * @Autowired MemoryManager manager;   // that's the whole integration
 * }</pre>
 */
@AutoConfiguration
@EnableConfigurationProperties(LangMem4jProperties.class)
@ConditionalOnClass(MemoryManager.class)
public class LangMem4jAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(MemoryStore.class)
    @ConditionalOnProperty(name = "langmem4j.store.type", havingValue = "inmemory", matchIfMissing = true)
    public InMemoryMemoryStore inMemoryMemoryStore() {
        return new InMemoryMemoryStore();
    }

    /**
     * Qdrant backend — loaded only when {@code langmem4j.store.type=qdrant}
     * AND the Qdrant gRPC client is on the classpath (optional dependency).
     * Requires an {@link EmbeddingGenerator} bean: Qdrant stores embeddings,
     * so a generator must be available to embed values on write.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(QdrantClient.class)
    @ConditionalOnProperty(name = "langmem4j.store.type", havingValue = "qdrant")
    static class QdrantStoreConfiguration {

        @Bean
        @ConditionalOnMissingBean(MemoryStore.class)
        public QdrantMemoryStore qdrantMemoryStore(
                LangMem4jProperties properties,
                ObjectProvider<EmbeddingGenerator> embeddingGenerator) {

            LangMem4jProperties.Qdrant q = properties.getStore().getQdrant();
            EmbeddingGenerator generator = embeddingGenerator.getIfAvailable();
            if (generator == null) {
                throw new IllegalStateException(
                        "langmem4j.store.type=qdrant requires an EmbeddingGenerator bean " +
                        "(Qdrant persists embeddings on write). Define one in your configuration.");
            }
            return QdrantMemoryStore.builder()
                    .host(q.getHost())
                    .port(q.getPort())
                    .useTls(q.isUseTls())
                    .apiKey(q.getApiKey() != null && !q.getApiKey().isBlank() ? q.getApiKey() : null)
                    .embeddingGenerator(generator)
                    .vectorSize(q.getVectorSize())
                    .build();
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public MemoryManager memoryManager(
            MemoryStore store,
            LangMem4jProperties properties,
            ObjectProvider<EmbeddingGenerator> embeddingGenerator,
            ObjectProvider<NamespaceResolver> namespaceResolver) {

        MemoryManager.Builder builder = MemoryManager.withStore(store)
                .withDefaultNamespace(properties.getDefaultNamespace());

        // Runtime namespace resolution (multi-tenant routing) — only wired
        // when a pattern is configured or the app defines its own resolver.
        NamespaceResolver resolver = namespaceResolver.getIfAvailable();
        if (resolver != null) {
            builder.withNamespaceResolver(resolver);
        }

        // Optional embedding generator bean — wire it in if the app defines one.
        EmbeddingGenerator generator = embeddingGenerator.getIfAvailable();
        if (generator != null) {
            builder.withEmbeddingGenerator(generator);
        }

        // Decay: exponential half-life with configurable prune threshold.
        LangMem4jProperties.Decay decay = properties.getDecay();
        if (decay.isEnabled()) {
            long halfLifeMs = decay.getHalfLife().toMillis();
            float pruneThreshold = decay.getPruneThreshold();
            MemoryDecayPolicy exponential = MemoryDecayPolicy.exponential(halfLifeMs);
            builder.withDecayPolicy(new MemoryDecayPolicy() {
                @Override
                public float decayFactor(long createdAt, long lastAccessedAt, long now) {
                    return exponential.decayFactor(createdAt, lastAccessedAt, now);
                }

                @Override
                public float pruneThreshold() {
                    return pruneThreshold;
                }
            });
        }

        // Merge: same-key rewrites via keyMerge().
        if (properties.getMerge().isEnabled()) {
            builder.withMergePolicy(MemoryMergePolicy.keyMerge());
        }

        // Compaction: manual-trigger fragment→summary.
        if (properties.getCompaction().isEnabled()) {
            String policy = properties.getCompaction().getPolicy();
            if ("llm".equalsIgnoreCase(policy)) {
                throw new IllegalStateException(
                        "langmem4j.compaction.policy=llm is not wired by the starter yet. " +
                        "Depend on langmem4j-strategy and register " +
                        "LlmSummarizationCompaction as a MemoryCompactionPolicy bean instead.");
            }
            builder.withCompactionPolicy(MemoryCompactionPolicy.categoryGroup());
        }

        return builder.build();
    }

    /**
     * Runtime namespace resolution from {@code langmem4j.namespace-pattern}
     * (SpEL template such as {@code user_#{#principal}}). Only created when
     * a pattern is set; a custom {@link NamespaceResolver} bean always wins.
     */
    @Bean
    @ConditionalOnMissingBean(NamespaceResolver.class)
    @ConditionalOnProperty("langmem4j.namespace-pattern")
    public NamespaceResolver namespaceResolver(
            LangMem4jProperties properties,
            ObjectProvider<NamespaceVariables> variables) {

        LangMem4jProperties.NamespaceCache cacheConfig = properties.getNamespaceCache();
        NamespaceResultCache cache = cacheConfig.isEnabled()
                ? new NamespaceResultCache(cacheConfig.getMaxSize(),
                        cacheConfig.getExpireAfterWrite().toMillis())
                : null;
        List<NamespaceVariables> providers = variables.orderedStream().toList();
        return new PatternNamespaceResolver(properties.getNamespacePattern(), providers, cache);
    }

    /**
     * {@code #principal} — the authenticated user name from Spring Security's
     * {@code SecurityContextHolder}. Only loaded when spring-security-core is
     * on the classpath.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(SecurityContextHolder.class)
    static class PrincipalVariablesConfiguration {

        @Bean
        public NamespaceVariables principalNamespaceVariables() {
            return () -> {
                Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
                return authentication != null
                        ? Map.of("principal", (Object) authentication.getName())
                        : Map.of();
            };
        }
    }

    /**
     * {@code #header['Name']} — a read-only map of the current request's HTTP
     * headers. Only loaded when spring-web is on the classpath; outside a
     * request (background jobs) it contributes nothing, letting the pattern
     * fall back to the default namespace (or an explicit {@code ?:} default).
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RequestContextHolder.class)
    static class RequestVariablesConfiguration {

        @Bean
        public NamespaceVariables requestNamespaceVariables() {
            return () -> {
                if (RequestContextHolder.getRequestAttributes()
                        instanceof ServletRequestAttributes servletAttributes) {
                    HttpServletRequest request = servletAttributes.getRequest();
                    // Case-insensitive keys: some clients/servers normalize header
                    // names to lower case, and templates must not care
                    // (#header['X-User-Id'] matches x-user-id too).
                    Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                    Enumeration<String> names = request.getHeaderNames();
                    while (names.hasMoreElements()) {
                        String name = names.nextElement();
                        headers.put(name, request.getHeader(name));
                    }
                    // Exposed as a single "header" map variable — templates read
                    // individual values via #header['X-User-Id']. Present (possibly
                    // empty) whenever a request is bound, so patterns with their
                    // own ?: default can still resolve; absent outside requests.
                    return Map.of("header", Collections.unmodifiableMap(headers));
                }
                return Map.of();
            };
        }
    }
}
