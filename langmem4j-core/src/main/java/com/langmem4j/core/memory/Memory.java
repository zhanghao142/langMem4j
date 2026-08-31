package com.langmem4j.core.memory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A single memory record identified by {@code namespace} + {@code key}.
 * <p>
 * This is the fundamental unit of long-term memory in langMem4j. A memory
 * is scoped to a namespace (e.g. per-user or per-session isolation), carries
 * a human-readable {@code value}, optional {@code metadata} tags, and an
 * optional {@code embeddingVector} for semantic retrieval.
 * <p>
 * All fields are immutable. {@code metadata} and {@code embeddingVector}
 * are defensively copied on construction.
 *
 * @param namespace        isolation scope; must not be blank
 * @param key              unique identifier within the namespace; must not be blank
 * @param value            the semantic content to remember; must not be blank
 * @param metadata         arbitrary key-value tags for filtering or display; may be empty
 * @param embeddingVector  pre-computed embedding, or {@code null} if not yet embedded
 */
public record Memory(
        String namespace,
        String key,
        String value,
        Map<String, Object> metadata,
        float[] embeddingVector
) {

    /**
     * Compact constructor enforcing null/blank checks and defensive copies.
     */
    public Memory {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("namespace must not be null or blank");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be null or blank");
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value must not be null or blank");
        }
        // Defensive copies: metadata unmodifiable, vector cloned.
        metadata = (metadata == null)
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new HashMap<>(metadata));
        embeddingVector = (embeddingVector == null)
                ? null
                : embeddingVector.clone();
    }

    /**
     * Convenience factory with no metadata and no embedding vector.
     */
    public static Memory of(String namespace, String key, String value) {
        return new Memory(namespace, key, value, null, null);
    }

    /**
     * Convenience factory with metadata but no embedding vector.
     */
    public static Memory of(String namespace, String key, String value, Map<String, Object> metadata) {
        return new Memory(namespace, key, value, metadata, null);
    }

    /**
     * Returns a new {@code Memory} copy with the given embedding vector set.
     */
    public Memory withEmbedding(float[] embeddingVector) {
        return new Memory(namespace, key, value, metadata, embeddingVector);
    }

    /**
     * Returns a new {@code Memory} copy with the given metadata merged into
     * existing metadata (new keys override existing ones).
     */
    public Memory withMetadata(Map<String, Object> extraMetadata) {
        if (extraMetadata == null || extraMetadata.isEmpty()) {
            return this;
        }
        Map<String, Object> merged = new HashMap<>(this.metadata);
        merged.putAll(extraMetadata);
        return new Memory(namespace, key, value, merged, embeddingVector);
    }

    // Override equals/hashCode to compare vectors by content, not reference identity.
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Memory other)) return false;
        return Objects.equals(namespace, other.namespace)
                && Objects.equals(key, other.key)
                && Objects.equals(value, other.value)
                && Objects.equals(metadata, other.metadata)
                && java.util.Arrays.equals(embeddingVector, other.embeddingVector);
    }

    @Override
    public int hashCode() {
        return Objects.hash(namespace, key, value, metadata, java.util.Arrays.hashCode(embeddingVector));
    }
}
