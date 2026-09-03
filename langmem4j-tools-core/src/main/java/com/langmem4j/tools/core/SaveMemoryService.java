package com.langmem4j.tools.core;

import com.langmem4j.core.memory.Memory;
import com.langmem4j.core.store.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Framework-agnostic helper for writing facts into a {@link MemoryStore}.
 * <p>
 * Pure Java — no {@code @Tool} / {@code @P} annotations, no LangChain4j
 * dependency. Exposes exactly the same string-centric contract as
 * {@code langmem4j-tools:SaveMemoryTool} so that callers using different
 * frameworks (Spring AI, custom function dispatch, etc.) can share its
 * behaviour.
 *
 * <pre>{@code
 * var svc = new SaveMemoryService(store, "user_alice");
 * String ok = svc.saveMemory("food", "Alice likes hot pot");
 * String ok2 = svc.saveMemoryWithMetadata("mood", "feeling great", "source=user,score=9");
 * String ok3 = svc.deleteMemory("food");
 * }</pre>
 *
 * @see com.langmem4j.tools.SaveMemoryTool the LangChain4j {@code @Tool}
 *      shim that delegates to this class
 */
public class SaveMemoryService {

    private static final Logger log = LoggerFactory.getLogger(SaveMemoryService.class);

    private final MemoryStore store;
    private final String namespace;

    public SaveMemoryService(MemoryStore store, String namespace) {
        if (store == null)     throw new IllegalArgumentException("store must not be null");
        if (namespace == null || namespace.isBlank())
            throw new IllegalArgumentException("namespace must not be blank");
        this.store = store;
        this.namespace = namespace;
    }

    /** Same contract as SaveMemoryTool#saveMemory. */
    public String saveMemory(String key, String content) {
        Memory memory = Memory.of(namespace, key, content);
        store.upsert(namespace, memory);
        log.info("saved ns={} key={}", namespace, key);
        return "Memory saved: key='" + key + "' → " + content;
    }

    /** Same contract as SaveMemoryTool#saveMemoryWithMetadata. */
    public String saveMemoryWithMetadata(String key, String content, String metadata) {
        Map<String, Object> meta = parseMetadata(metadata);
        Memory memory = Memory.of(namespace, key, content, meta);
        store.upsert(namespace, memory);
        log.info("saved ns={} key={} metadata={}", namespace, key, meta);
        return "Memory saved: key='" + key + "' → " + content
                + (meta.isEmpty() ? "" : " [metadata=" + meta + "]");
    }

    /** Same contract as SaveMemoryTool#deleteMemory. */
    public String deleteMemory(String key) {
        store.deleteByKey(namespace, key);
        log.info("deleted ns={} key={}", namespace, key);
        return "Memory with key '" + key + "' has been deleted.";
    }

    /** Read-only access to the bound namespace — useful for logging. */
    public String namespace() {
        return namespace;
    }

    /** Shared with SearchMemoryService: tolerant key=value parser. */
    static Map<String, Object> parseMetadata(String metadata) {
        Map<String, Object> result = new HashMap<>();
        if (metadata == null || metadata.isBlank()) return result;
        for (String pair : metadata.split(",")) {
            String trimmed = pair.trim();
            if (trimmed.isEmpty()) continue;
            int eq = trimmed.indexOf('=');
            if (eq > 0) {
                String k = trimmed.substring(0, eq).trim();
                String v = trimmed.substring(eq + 1).trim();
                result.put(k, v);
            } else {
                result.put(trimmed, Boolean.TRUE); // boolean-style tag
            }
        }
        return result;
    }
}
