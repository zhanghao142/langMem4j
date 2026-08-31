package com.langmem4j.tools;

import com.langmem4j.tools.core.SaveMemoryService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * LangChain4j {@code @Tool} shim that delegates 1:1 to the framework-free
 * {@link SaveMemoryService}.
 * <p>
 * Kept intentionally thin — swap for
 * {@code com.langmem4j.tools.core.SaveMemoryService} directly if you are
 * wiring function calls without LangChain4j.
 */
public class SaveMemoryTool {

    private final SaveMemoryService delegate;

    public SaveMemoryTool(com.langmem4j.core.store.MemoryStore store, String namespace) {
        this.delegate = new SaveMemoryService(store, namespace);
    }

    @Tool(value = "Save a factual memory for later retrieval. "
            + "Use when the user asks to remember, save, or note something. "
            + "Key should be a short stable identifier; content should be the full fact.",
            name = "save_memory")
    public String saveMemory(
            @P("A short stable identifier for this memory, e.g. 'user_birthday'")
            String key,
            @P("The factual content to remember, e.g. 'Alice was born on March 15'")
            String content) {
        return delegate.saveMemory(key, content);
    }

    @Tool(value = "Save a factual memory with optional metadata tags. "
            + "Use when you need to attach extra context (source, category, etc.).",
            name = "save_memory_with_metadata")
    public String saveMemoryWithMetadata(
            @P("A short stable identifier for this memory")
            String key,
            @P("The factual content to remember")
            String content,
            @P("Optional metadata as key=value pairs separated by commas, "
                    + "e.g. 'source=user,category=preference'")
            String metadata) {
        return delegate.saveMemoryWithMetadata(key, content, metadata);
    }

    @Tool(value = "Delete a previously saved memory by its key. "
            + "Use when the user asks you to forget or remove something.",
            name = "delete_memory")
    public String deleteMemory(
            @P("The key of the memory to delete")
            String key) {
        return delegate.deleteMemory(key);
    }
}
