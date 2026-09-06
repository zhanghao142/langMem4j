package com.example.langmem4j.springboot.web;

import com.langmem4j.core.manager.MemoryManager;
import com.langmem4j.core.memory.Memory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Thin REST façade over the auto-configured {@link MemoryManager}.
 * In a real agent you would hand the manager (or the LangChain4j @Tool layer)
 * to your LLM instead of exposing HTTP — this controller makes the behaviour
 * observable and testable without an API key.
 */
@RestController
@RequestMapping("/api/memories")
public class MemoryController {

    private final MemoryManager manager;

    public MemoryController(MemoryManager manager) {
        this.manager = manager;
    }

    /** Saves a memory under the default namespace. */
    @PostMapping
    public Map<String, Object> add(@RequestBody AddRequest request) {
        manager.add(request.key(), request.value(), request.metadata());
        return Map.of("status", "saved", "key", request.key());
    }

    /** Substring/semantic search with decay re-ranking applied. */
    @GetMapping("/search")
    public List<Memory> search(@RequestParam String q,
                               @RequestParam(defaultValue = "5") int limit) {
        return manager.search(q, limit);
    }

    /** Exact-key lookup; also refreshes lastAccessedAt (extends lifespan). */
    @GetMapping("/{key}")
    public Optional<Memory> get(@PathVariable String key) {
        return manager.get(key);
    }

    /** Manual compaction trigger: fragments → per-category summaries. */
    @PostMapping("/compact")
    public Map<String, Object> compact() {
        manager.compact();
        return Map.of("status", "compacted", "keys", manager.keys());
    }

    @DeleteMapping("/{key}")
    public Map<String, Object> remove(@PathVariable String key) {
        manager.remove(key);
        return Map.of("status", "deleted", "key", key);
    }

    public record AddRequest(String key, String value, Map<String, Object> metadata) {}
}
