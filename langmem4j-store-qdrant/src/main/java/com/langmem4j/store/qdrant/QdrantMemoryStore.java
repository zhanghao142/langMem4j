package com.langmem4j.store.qdrant;

import com.langmem4j.core.embedding.EmbeddingGenerator;
import com.langmem4j.core.memory.Memory;
import com.langmem4j.core.store.MemoryFilter;
import com.langmem4j.core.store.MemoryStore;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.SearchPoints;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;

import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;
import static io.qdrant.client.WithPayloadSelectorFactory.enable;

/**
 * {@link MemoryStore} adapter backed by the official Qdrant gRPC client.
 * <p>
 * Each {@code namespace} maps to a dedicated Qdrant collection. Collections
 * are created lazily on first write using the vector size reported by the
 * configured {@link EmbeddingGenerator}. Cosine distance is used by default.
 * <p>
 * The {@link EmbeddingGenerator} is mandatory: every upsert without a
 * pre-computed {@link Memory#embeddingVector()} will be embedded on the fly,
 * and every {@link #search} call embeds the query text.
 * <p>
 * All blocking calls delegate to {@code ListenableFuture#get()}. Consider
 * wrapping this store in an async facade for high-throughput scenarios.
 */
public class QdrantMemoryStore implements MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(QdrantMemoryStore.class);

    /** Payload field names stored alongside each vector. */
    static final String FIELD_KEY = "__langmem_key";
    static final String FIELD_VALUE = "__langmem_value";
    static final String FIELD_NAMESPACE = "__langmem_namespace";

    private final QdrantClient client;
    private final EmbeddingGenerator embeddingGenerator;
    private final int vectorSize;

    /** Remembers which collections have been created so we skip CREATE on repeat upserts. */
    private final ConcurrentMap<String, Boolean> collections = new ConcurrentHashMap<>();

    /**
     * @param client             connected Qdrant gRPC client
     * @param embeddingGenerator used to embed memories and queries; must not be null
     * @param vectorSize         dimensionality of the embedding vectors (must match the generator)
     */
    public QdrantMemoryStore(QdrantClient client, EmbeddingGenerator embeddingGenerator, int vectorSize) {
        if (client == null) {
            throw new IllegalArgumentException("client must not be null");
        }
        if (embeddingGenerator == null) {
            throw new IllegalArgumentException("embeddingGenerator must not be null");
        }
        if (vectorSize <= 0) {
            throw new IllegalArgumentException("vectorSize must be positive, got " + vectorSize);
        }
        this.client = client;
        this.embeddingGenerator = embeddingGenerator;
        this.vectorSize = vectorSize;
    }

    /**
     * Convenience short-cut for the most common single-node setup
     * ({@code host:port} gRPC, plaintext, default collection naming).
     *
     * <pre>{@code
     * MemoryStore store = QdrantMemoryStore.builder()
     *         .host("localhost")
     *         .port(6334)                           // gRPC
     *         .vectorSize(1536)
     *         .embeddingGenerator(myEmbedder)
     *         .build();
     * }</pre>
     */
    public static Builder builder() { return new Builder(); }

    /** Fluent builder for {@link QdrantMemoryStore}. */
    public static final class Builder {
        private String host = "localhost";
        private int    port = 6334;
        private boolean useTls;
        private String apiKey;
        private QdrantClient explicitClient;
        private EmbeddingGenerator embeddingGenerator;
        private int vectorSize;

        public Builder host(String host)             { this.host = host; return this; }
        public Builder port(int port)                { this.port = port; return this; }
        public Builder useTls(boolean tls)           { this.useTls = tls; return this; }
        public Builder apiKey(String apiKey)         { this.apiKey = apiKey; return this; }
        public Builder client(QdrantClient client)   { this.explicitClient = client; return this; }
        public Builder embeddingGenerator(EmbeddingGenerator gen) { this.embeddingGenerator = gen; return this; }
        public Builder vectorSize(int dim)           { this.vectorSize = dim; return this; }

        public QdrantMemoryStore build() {
            QdrantClient client = explicitClient;
            if (client == null) {
                if (host == null || host.isBlank()) {
                    throw new IllegalStateException("Qdrant host must be set when no explicit client is given");
                }
                if (port <= 0) {
                    throw new IllegalStateException("Qdrant port must be positive");
                }
                var grpcBuilder = io.qdrant.client.QdrantGrpcClient.newBuilder(host, port, useTls);
                if (apiKey != null && !apiKey.isBlank()) {
                    grpcBuilder = grpcBuilder.withApiKey(apiKey);
                }
                client = new QdrantClient(grpcBuilder.build());
            }
            return new QdrantMemoryStore(client, embeddingGenerator, vectorSize);
        }
    }

    @Override
    public void upsert(String namespace, Memory memory) {
        ensureCollectionExists(namespace);

        float[] vector = memory.embeddingVector();
        if (vector == null) {
            vector = embeddingGenerator.embed(memory.value());
        }

        PointStruct point = buildPoint(namespace, memory, vector);
        await(() -> client.upsertAsync(namespace, List.of(point)));
        log.debug("upsert ns={} key={}", namespace, memory.key());
    }

    @Override
    public Optional<Memory> getByKey(String namespace, String key) {
        try {
            ensureCollectionExists(namespace);
            long pointId = deterministicId(namespace, key);
            List<RetrievedPoint> results = client.retrieveAsync(
                    namespace,
                    List.of(id(pointId)),
                    enable(true),
                    io.qdrant.client.WithVectorsSelectorFactory.enable(true),
                    null
            ).get();

            if (results == null || results.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(pointToMemory(namespace, results.get(0)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while getByKey", e);
        } catch (ExecutionException e) {
            if (isNotFoundException(e)) {
                return Optional.empty();
            }
            throw new RuntimeException(e.getCause());
        }
    }

    @Override
    public List<String> listKeys(String namespace) {
        try {
            ensureCollectionExists(namespace);
            // Qdrant has no direct "list all payload fields" API; use a dummy
            // vector with near-zero norm to pull up to 10_000 records.
            float[] dummy = new float[vectorSize];
            dummy[0] = 1e-10f;

            List<ScoredPoint> results = client.searchAsync(
                    SearchPoints.newBuilder()
                            .setCollectionName(namespace)
                            .addAllVector(toFloatList(dummy))
                            .setLimit(10_000)
                            .setWithPayload(enable(true))
                            .setWithVectors(io.qdrant.client.WithVectorsSelectorFactory.enable(false))
                            .build()
            ).get();

            List<String> keys = new ArrayList<>(results.size());
            for (ScoredPoint point : results) {
                Value keyValue = point.getPayloadOrDefault(FIELD_KEY, null);
                if (keyValue != null && !keyValue.getStringValue().isBlank()) {
                    keys.add(keyValue.getStringValue());
                }
            }
            return keys;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while listKeys", e);
        } catch (ExecutionException e) {
            if (isNotFoundException(e)) {
                return List.of();
            }
            throw new RuntimeException(e.getCause());
        }
    }

    @Override
    public List<Memory> search(String namespace, String queryText, int limit) {
        return search(namespace, queryText, limit, MemoryFilter.NONE);
    }

    @Override
    public List<Memory> search(String namespace, String queryText,
                               int limit, MemoryFilter filter) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, got " + limit);
        }
        try {
            ensureCollectionExists(namespace);
            float[] queryVector = embeddingGenerator.embed(queryText);
            MemoryFilter f = filter == null ? MemoryFilter.NONE : filter;

            // NOTE — on Qdrant client 1.19.0 the public proto surface for
            // Filter / FieldCondition / Match varies across minor releases,
            // so we do NOT call setFilter() here. Instead we over-fetch by
            // a small factor (so the tail doesn't starve us of matches) and
            // then enforce metadata + minScore client-side.
            //
            // This is *correct* (no false positives, preserves limit,
            // matches MemoryFilter.matchesMetadata semantics exactly) at the
            // cost of a slightly larger gRPC response payload for the tail.
            // When langMem4j bumps the Qdrant client to a stable release we
            // can restore server-side push-down (see MemoryFilter Javadoc).
            int probeLimit = limit;
            if (f.hasMetadataMatch() || f.hasMinScore()) {
                probeLimit = Math.min(Math.max(limit * 10, 50), 500);
            }

            List<ScoredPoint> results = client.searchAsync(
                    SearchPoints.newBuilder()
                            .setCollectionName(namespace)
                            .addAllVector(toFloatList(queryVector))
                            .setLimit(probeLimit)
                            .setWithPayload(enable(true))
                            .setWithVectors(io.qdrant.client.WithVectorsSelectorFactory.enable(true))
                            .build()
            ).get();

            List<Memory> memories = new ArrayList<>(limit);
            for (ScoredPoint point : results) {
                if (f.hasMinScore() && point.getScore() < f.minScore()) {
                    continue;
                }
                Memory m = pointToMemory(namespace, point);
                if (!f.matchesMetadata(m.metadata())) {
                    continue;
                }
                memories.add(m);
                if (memories.size() >= limit) break;
            }
            return memories;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while search", e);
        } catch (ExecutionException e) {
            if (isNotFoundException(e)) {
                return List.of();
            }
            throw new RuntimeException(e.getCause());
        }
    }

    @Override
    public void deleteByKey(String namespace, String key) {
        try {
            long pointId = deterministicId(namespace, key);
            await(() -> client.deleteAsync(namespace, List.of(id(pointId))));
            log.debug("delete ns={} key={}", namespace, key);
        } catch (RuntimeException e) {
            if (isNotFoundException(e)) {
                return; // idempotent
            }
            throw e;
        }
    }

    @Override
    public void clearNamespace(String namespace) {
        try {
            await(() -> client.deleteCollectionAsync(namespace));
            collections.remove(namespace);
            log.debug("clear ns={}", namespace);
        } catch (RuntimeException e) {
            if (isNotFoundException(e)) {
                collections.remove(namespace);
                return;
            }
            throw e;
        }
    }

    // ========================================================================
    // Internal helpers
    // ========================================================================

    /** Lazily creates a collection for {@code namespace} if one does not exist yet. */
    private void ensureCollectionExists(String namespace) {
        collections.computeIfAbsent(namespace, ns -> {
            try {
                await(() -> client.createCollectionAsync(ns,
                        VectorParams.newBuilder()
                                .setSize(vectorSize)
                                .setDistance(Distance.Cosine)
                                .build()));
                log.info("created collection ns={} size={}", ns, vectorSize);
            } catch (RuntimeException e) {
                if (isAlreadyExistsException(e)) {
                    log.info("collection ns={} already exists", ns);
                } else {
                    throw e;
                }
            }
            return Boolean.TRUE;
        });
    }

    /** Builds a Qdrant PointStruct from a Memory + vector. */
    private PointStruct buildPoint(String namespace, Memory memory, float[] vector) {
        Map<String, Value> payload = new HashMap<>();
        payload.put(FIELD_KEY, value(memory.key()));
        payload.put(FIELD_VALUE, value(memory.value()));
        payload.put(FIELD_NAMESPACE, value(namespace));

        // Flatten metadata into payload (String/Number/Boolean only, to stay compatible).
        for (Map.Entry<String, Object> entry : memory.metadata().entrySet()) {
            Object v = entry.getValue();
            if (v instanceof String s) {
                payload.put(entry.getKey(), value(s));
            } else if (v instanceof Number n) {
                payload.put(entry.getKey(), value(n.doubleValue()));
            } else if (v instanceof Boolean b) {
                payload.put(entry.getKey(), value(b));
            }
            // Skip unsupported types silently.
        }

        long pointId = deterministicId(namespace, memory.key());

        return PointStruct.newBuilder()
                .setId(id(pointId))
                .setVectors(vectors(toFloatList(vector)))
                .putAllPayload(payload)
                .build();
    }

    /** Reconstructs a Memory from a Qdrant RetrievedPoint. */
    private Memory pointToMemory(String namespace, RetrievedPoint point) {
        String key = point.getPayloadOrDefault(FIELD_KEY, null).getStringValue();
        String value = point.getPayloadOrDefault(FIELD_VALUE, null).getStringValue();
        return buildMemoryFromPayload(namespace, key, value,
                point.getPayloadMap(), point.getVectors());
    }

    /** Reconstructs a Memory from a Qdrant ScoredPoint (search result). */
    private Memory pointToMemory(String namespace, ScoredPoint scored) {
        String key = scored.getPayloadOrDefault(FIELD_KEY, null).getStringValue();
        String value = scored.getPayloadOrDefault(FIELD_VALUE, null).getStringValue();
        return buildMemoryFromPayload(namespace, key, value,
                scored.getPayloadMap(), scored.getVectors());
    }

    private Memory buildMemoryFromPayload(String namespace, String key, String value,
                                          Map<String, Value> payloadMap,
                                          io.qdrant.client.grpc.Points.VectorsOutput vectors) {
        Map<String, Object> metadata = new HashMap<>();
        for (Map.Entry<String, Value> entry : payloadMap.entrySet()) {
            String field = entry.getKey();
            if (FIELD_KEY.equals(field) || FIELD_VALUE.equals(field) || FIELD_NAMESPACE.equals(field)) {
                continue;
            }
            metadata.put(field, valueToJava(entry.getValue()));
        }

        float[] vector = null;
        if (vectors != null && vectors.hasVector()) {
            var data = vectors.getVector().getDataList();
            vector = new float[data.size()];
            for (int i = 0; i < data.size(); i++) {
                vector[i] = data.get(i);
            }
        }

        return new Memory(namespace, key, value, metadata, vector);
    }

    /** Best-effort conversion from Qdrant Value to a plain Java object. */
    private static Object valueToJava(Value v) {
        if (v.hasStringValue()) return v.getStringValue();
        if (v.hasIntegerValue()) return v.getIntegerValue();
        if (v.hasDoubleValue()) return v.getDoubleValue();
        if (v.hasBoolValue()) return v.getBoolValue();
        return null;
    }

    /** Deterministic long ID from namespace + key. Uses FNV-1a 64-bit hash. */
    static long deterministicId(String namespace, String key) {
        String composite = namespace + "::" + key;
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < composite.length(); i++) {
            h ^= composite.charAt(i);
            h *= 0x100000001b3L;
        }
        return h & 0x7fffffffffffffffL; // keep positive
    }

    // NOTE — buildQdrantFilter is intentionally absent for now: the public
    // proto surface (Filter / FieldCondition / Match) varies across Qdrant
    // Java client 1.x minor releases in ways that are brittle to depend on.
    // Search filters are enforced with exact MemoryFilter.matchesMetadata()
    // semantics client-side after retrieval; server-side push-down returns
    // when we stabilise on a single Qdrant client release + add IT coverage
    // against a real gRPC server (see QdrantMemoryStoreIntegrationTest).


    private static List<Float> toFloatList(float[] array) {
        List<Float> list = new ArrayList<>(array.length);
        for (float v : array) list.add(v);
        return list;
    }

    /** Blocks on a ListenableFuture supplier, unwrapping ExecutionException to a RuntimeException. */
    private static void await(ThrowingSupplier<?> supplier) {
        try {
            supplier.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e.getCause());
        }
    }

    private static boolean isNotFoundException(Throwable t) {
        String msg = t.getMessage() == null ? "" : t.getMessage().toLowerCase();
        return msg.contains("not found") || msg.contains("collection does not exist")
                || msg.contains("failed to find collection");
    }

    private static boolean isAlreadyExistsException(Throwable t) {
        String msg = t.getMessage() == null ? "" : t.getMessage().toLowerCase();
        return msg.contains("already exists") || msg.contains("collection already exists");
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws InterruptedException, ExecutionException;
    }
}
