package com.langmem4j.tools.core;

import com.langmem4j.core.memory.Memory;
import com.langmem4j.core.store.MemoryFilter;
import com.langmem4j.core.store.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Framework-agnostic helper for reading from / searching a
 * {@link MemoryStore}.
 * <p>
 * Pure Java — no {@code @Tool} / {@code @P} annotations, no LangChain4j
 * dependency. Mirrors every output format and edge-case behaviour of
 * {@code langmem4j-tools:SearchMemoryTool} so they stay in lock-step.
 *
 * <pre>{@code
 * var svc = new SearchMemoryService(store, "user_alice");
 * String got    = svc.getMemory("food");
 * String hits   = svc.searchMemory("What does Alice like to eat?", 5);
 * String filtered = svc.searchMemory("food", 5, "category=preference,source=user");
 * String keys   = svc.listMemories();
 * }</pre>
 *
 * @see com.langmem4j.tools.SearchMemoryTool the LangChain4j shim
 */
public class SearchMemoryService {

    private static final Logger log = LoggerFactory.getLogger(SearchMemoryService.class);

    private final MemoryStore store;
    private final String namespace;

    public SearchMemoryService(MemoryStore store, String namespace) {
        if (store == null) throw new IllegalArgumentException("store must not be null");
        if (namespace == null || namespace.isBlank())
            throw new IllegalArgumentException("namespace must not be blank");
        this.store = store;
        this.namespace = namespace;
    }

    /** Same contract as SearchMemoryTool#getMemory. */
    public String getMemory(String key) {
        Optional<Memory> result = store.getByKey(namespace, key);
        if (result.isEmpty()) {
            log.debug("getMemory miss ns={} key={}", namespace, key);
            return "No memory found with key '" + key + "'.";
        }
        Memory m = result.get();
        log.debug("getMemory hit ns={} key={}", namespace, key);
        StringBuilder sb = new StringBuilder();
        sb.append("Found memory: key='").append(m.key()).append("' → ").append(m.value());
        if (!m.metadata().isEmpty()) {
            sb.append(" [metadata=").append(m.metadata()).append("]");
        }
        return sb.toString();
    }

    /** Same contract as SearchMemoryTool#searchMemory (no metadata filter). */
    public String searchMemory(String query, Integer limit) {
        return searchMemory(query, limit, (String) null);
    }

    /**
     * Extended variant: the caller may pass a metadata filter string in
     * {@code key=value,key=value} format. Values are string-only; the
     * resulting {@link MemoryFilter} is AND-semantics.
     *
     * @param query     free-text search query
     * @param limit     cap on returned results (1–20, clamped by the impl)
     * @param metadata  optional {@code key=value,...} requirements, or null
     */
    public String searchMemory(String query, Integer limit, String metadata) {
        int effectiveLimit = (limit == null || limit < 1) ? 5 : Math.min(limit, 20);
        MemoryFilter filter = MemoryFilter.NONE;
        Map<String, Object> md = SaveMemoryService.parseMetadata(metadata);
        if (!md.isEmpty()) {
            filter = MemoryFilter.metadata(md);
        }
        List<Memory> results = store.search(namespace, query, effectiveLimit, filter);
        log.debug("searchMemory ns={} query='{}' filter={} results={}",
                namespace, query, md, results.size());

        if (results.isEmpty()) {
            return "No memories found matching '" + query + "'.";
        }
        return results.stream()
                .map(SearchMemoryService::formatOne)
                .collect(Collectors.joining("\n"));
    }

    /** Same contract as SearchMemoryTool#listMemories. */
    public String listMemories() {
        List<String> keys = store.listKeys(namespace);
        if (keys.isEmpty()) {
            return "No memories are stored yet.";
        }
        return "Stored memories (" + keys.size() + "): " + String.join(", ", keys);
    }

    public String namespace() {
        return namespace;
    }

    static String formatOne(Memory m) {
        StringBuilder sb = new StringBuilder();
        sb.append("- ").append(m.key()).append(": ").append(m.value());
        if (!m.metadata().isEmpty()) {
            sb.append(" [").append(m.metadata()).append("]");
        }
        return sb.toString();
    }
}
