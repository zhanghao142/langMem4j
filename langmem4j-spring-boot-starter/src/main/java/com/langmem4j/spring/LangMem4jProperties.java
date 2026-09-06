package com.langmem4j.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration properties bound to the {@code langmem4j.*} prefix.
 *
 * <pre>{@code
 * langmem4j:
 *   default-namespace: user_alice
 *   namespace-pattern: "user_#{#principal}"   # runtime resolution (optional)
 *   namespace-cache:
 *     enabled: true
 *     max-size: 1000
 *     expire-after-write: 10m
 *   store:
 *     type: inmemory          # inmemory | qdrant
 *     qdrant:
 *       host: localhost
 *       port: 6334
 *       api-key: ${QDRANT_API_KEY:}
 *       use-tls: false
 *       vector-size: 1536
 *   decay:
 *     enabled: true
 *     half-life: 7d
 *     prune-threshold: 0.01
 *   merge:
 *     enabled: true
 *   compaction:
 *     enabled: false
 *     policy: category-group  # category-group (V1; "llm" requires manual wiring)
 * }</pre>
 */
@ConfigurationProperties(prefix = "langmem4j")
public class LangMem4jProperties {

    private String defaultNamespace = "default";

    /**
     * SpEL template for runtime namespace resolution, e.g.
     * {@code user_#{#principal}} or
     * {@code tenant_#{#header['X-Tenant-Id'] ?: 'anonymous'}}.
     * When set, every no-namespace MemoryManager call routes to the
     * namespace rendered from the current context; a null/blank render (or a
     * missing referenced variable) falls back to {@code default-namespace}.
     * Leave unset for the classic static default namespace.
     */
    private String namespacePattern;

    private final NamespaceCache namespaceCache = new NamespaceCache();
    private final Store store = new Store();
    private final Decay decay = new Decay();
    private final Merge merge = new Merge();
    private final Compaction compaction = new Compaction();

    public String getDefaultNamespace() { return defaultNamespace; }
    public void setDefaultNamespace(String defaultNamespace) { this.defaultNamespace = defaultNamespace; }

    public String getNamespacePattern() { return namespacePattern; }
    public void setNamespacePattern(String namespacePattern) { this.namespacePattern = namespacePattern; }

    public NamespaceCache getNamespaceCache() { return namespaceCache; }
    public Store getStore() { return store; }
    public Decay getDecay() { return decay; }
    public Merge getMerge() { return merge; }
    public Compaction getCompaction() { return compaction; }

    /**
     * Result cache for {@code namespace-pattern} resolution. Only applies to
     * patterns that reference nothing but {@code #principal} (results are a
     * pure function of the principal, so caching cannot cross tenants).
     */
    public static class NamespaceCache {
        /** When true, principal-only patterns cache their rendered result. */
        private boolean enabled = true;
        /** Maximum number of cached results (LRU eviction). */
        private int maxSize = 1000;
        /** TTL of a cached result. */
        private Duration expireAfterWrite = Duration.ofMinutes(10);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxSize() { return maxSize; }
        public void setMaxSize(int maxSize) { this.maxSize = maxSize; }
        public Duration getExpireAfterWrite() { return expireAfterWrite; }
        public void setExpireAfterWrite(Duration expireAfterWrite) { this.expireAfterWrite = expireAfterWrite; }
    }

    /** Storage backend selection. */
    public static class Store {
        /** Backend type: {@code inmemory} (default) or {@code qdrant}. */
        private String type = "inmemory";

        private final Qdrant qdrant = new Qdrant();

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public Qdrant getQdrant() { return qdrant; }
    }

    /** Qdrant gRPC connection settings (only used when {@code store.type=qdrant}). */
    public static class Qdrant {
        private String host = "localhost";
        private int port = 6334;
        private String apiKey;
        private boolean useTls = false;
        private int vectorSize = 1536;

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public boolean isUseTls() { return useTls; }
        public void setUseTls(boolean useTls) { this.useTls = useTls; }
        public int getVectorSize() { return vectorSize; }
        public void setVectorSize(int vectorSize) { this.vectorSize = vectorSize; }
    }

    /** Time-driven decay policy settings. */
    public static class Decay {
        /** When false (default), no decay policy is applied. */
        private boolean enabled = false;
        /** Half-life of memory relevance; factor halves every this period. */
        private Duration halfLife = Duration.ofDays(7);
        /** Memories below this decay factor are hidden from search results. */
        private float pruneThreshold = 0.01f;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Duration getHalfLife() { return halfLife; }
        public void setHalfLife(Duration halfLife) { this.halfLife = halfLife; }
        public float getPruneThreshold() { return pruneThreshold; }
        public void setPruneThreshold(float pruneThreshold) { this.pruneThreshold = pruneThreshold; }
    }

    /** Same-key merge policy settings. */
    public static class Merge {
        /** When true, rewrites of an existing key merge via keyMerge(). */
        private boolean enabled = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    /** Fragment-to-summary compaction settings (manual trigger via compact()). */
    public static class Compaction {
        /** When true, a MemoryCompactionPolicy bean is wired for compact(). */
        private boolean enabled = false;
        /**
         * Compaction policy: {@code category-group} (pure Java, V1) or
         * {@code llm} (reserved for future versions — requires a ChatModel
         * bean; wire LlmSummarizationCompaction manually for now).
         */
        private String policy = "category-group";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getPolicy() { return policy; }
        public void setPolicy(String policy) { this.policy = policy; }
    }
}
