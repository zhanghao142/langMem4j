package com.langmem4j.core.embedding;

/**
 * SPI for generating embedding vectors from text.
 * <p>
 * Implementations are provided by framework-specific modules (e.g. a
 * LangChain4j adapter that delegates to {@code EmbeddingModel}). The
 * {@code MemoryStore} SPI accepts an {@code EmbeddingGenerator} so that
 * storage adapters can generate vectors without coupling to any particular
 * embedding backend.
 */
@FunctionalInterface
public interface EmbeddingGenerator {

    /**
     * Generates a dense float vector embedding for the given text.
     *
     * @param text the source text; never null or blank
     * @return a float array whose length matches the configured vector size
     */
    float[] embed(String text);
}
