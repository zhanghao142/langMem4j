/**
 * Qdrant-backed {@link com.langmem4j.core.store.MemoryStore} implementation.
 * Maps MemoryStore SPI operations onto qdrant-client-java calls, generating
 * embeddings via an injected {@code EmbeddingGenerator} SPI.
 */
package com.langmem4j.store.qdrant;
