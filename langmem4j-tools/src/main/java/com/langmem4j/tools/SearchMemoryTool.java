package com.langmem4j.tools;

import com.langmem4j.tools.core.SearchMemoryService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * LangChain4j {@code @Tool} shim that delegates 1:1 to the framework-free
 * {@link SearchMemoryService}.
 * <p>
 * Kept intentionally thin — swap for
 * {@code com.langmem4j.tools.core.SearchMemoryService} directly if you are
 * wiring function calls without LangChain4j.
 */
public class SearchMemoryTool {

    private final SearchMemoryService delegate;

    public SearchMemoryTool(com.langmem4j.core.store.MemoryStore store, String namespace) {
        this.delegate = new SearchMemoryService(store, namespace);
    }

    @Tool(value = "Retrieve a previously saved memory by its exact key. "
            + "Use when you know the key (e.g. from a prior save_memory call). "
            + "If you only have a vague description of what you need, use search_memory instead.",
            name = "get_memory")
    public String getMemory(
            @P("The exact key of the memory to retrieve")
            String key) {
        return delegate.getMemory(key);
    }

    @Tool(value = "Search for memories semantically using a free-text query. "
            + "Use when you don't know the exact key but have a description of what to find. "
            + "Returns the most relevant saved facts. Optionally narrow results by passing "
            + "metadata as key=value pairs separated by commas.",
            name = "search_memory")
    public String searchMemory(
            @P("Free-text description of what to look for, "
                    + "e.g. 'what does Alice like to eat'")
            String query,
            @P("Maximum number of results to return, between 1 and 20")
            Integer limit,
            @P("Optional metadata filter as key=value pairs separated by commas, "
                    + "e.g. 'category=preference,source=user'. Leave null or blank to skip.")
            String metadata) {
        return delegate.searchMemory(query, limit, metadata);
    }

    /** Backwards-compatible 2-arg overload for callers unaware of metadata filtering. */
    public String searchMemory(String query, Integer limit) {
        return delegate.searchMemory(query, limit);
    }

    @Tool(value = "List all memory keys currently stored. "
            + "Use for exploration or discovery when you don't know what's been saved.",
            name = "list_memories")
    public String listMemories() {
        return delegate.listMemories();
    }
}
