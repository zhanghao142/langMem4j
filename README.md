# langMem4j

> Long-term memory middleware for the Java ecosystem.

Pluggable long-term memory (Facts / Preferences / Patterns) with semantic retrieval for LLM agents.
Use standalone, or expose as tool functions to **LangChain4j** / **Spring AI**.

***

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│  Your LLM Agent / Application                                       │
├──────────────────────┬──────────────────────────────────────────────┤
│  LangChain4j @Tool   │  Spring AI / Custom JSON-RPC                 │
│  (langmem4j-tools)   │  (langmem4j-tools-core, zero-framework)      │
│  SaveMemoryTool      │  SaveMemoryService / SearchMemoryService     │
│  SearchMemoryTool    │  "a=b,c=d" parsing / limit clamp / string I/O│
└──────────┬───────────┴──────────────────────────────────────────────┘
           │  add() / search() / get() / remove() / keys()
           ▼
┌─────────────────────────────────────────────────────────────────────┐
│  MemoryManager  (single facade — Builder pattern)                   │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │ withStore(store)           ← pick backend                      │ │
│  │ withEmbeddingGenerator()   ← pick embedding model              │ │
│  │ withDecayPolicy()          ← memory decay (exponential/custom) │ │
│  │ withMergePolicy()          ← memory merge (keyMerge/custom)    │ │
│  │ withCompactionPolicy()     ← fragment→summary compaction       │ │
│  │ withDefaultNamespace()     ← tenant isolation                  │ │
│  └────────────────────────────────────────────────────────────────┘ │
│  add → enrich(missing embedding auto-filled) → applyMerge → upsert  │
│  search → store.search → applyDecay(filter + re-rank by freshness)  │
│  get → getByKey → refresh lastAccessedAt ("access extends lifespan")│
│  compact → listKeys → policy.compact → delete old → upsert summary  │
└────┬────────────┬──────────────┬───────────────────┬────────────────┘
     ▼            ▼              ▼                   ▼
┌──────────┐ ┌───────────┐ ┌────────────────┐ ┌───────────────────────┐
│ Memory   │ │ Embedding │ │ MemoryStore    │ │ MemoryFilter          │
│ record   │ │ Generator │ │ SPI            │ │ metadata(AND)+minScore│
│ 7 fields │ │ float[]   │ │ upsert/get/    │ │ matchesMetadata()     │
│ immutable│ │ embed(txt)│ │ search/delete  │ │                       │
└──────────┘ └───────────┘ └───────┬────────┘ └───────────────────────┘
                                   │
                    ┌──────────────┼──────────────┐
                    ▼              ▼              ▼
          ┌──────────────┐ ┌────────────┐ ┌──────────────────┐
          │ InMemory     │ │ Qdrant     │ │ langgraph4j      │
          │ (zero-dep)   │ │ gRPC 1.19  │ │ Store SPI 1.8.25 │
          │ substring +  │ │ probe×10 + │ │ Store.Item ↔     │
          │ cosine dual  │ │ client flt │ │ Memory mapping   │
          └──────────────┘ └────────────┘ └──────────────────┘
```

**5 SPIs, 1 facade, 3 storage backends — all pluggable, switch by changing one Builder line.**

***

## ✨ Features

- **One entry point, 5 SPIs** — `MemoryManager` is the only class you need; swap backends via `MemoryStore`, embedding models via `EmbeddingGenerator`, decay via `MemoryDecayPolicy`, merge via `MemoryMergePolicy`, compaction via `MemoryCompactionPolicy`

- **Namespace-native isolation** — `user_alice / user_bob / agent_prod` under the same store never interfere; no key-prefix hacks

- **SPI-level Metadata Filter (V1)** — `MemoryStore.search(ns, query, limit, MemoryFilter)` with AND semantics + minScore across all backends:

  - InMemoryMemoryStore: pre-filters candidates before ranking (zero network cost, strictest)

  - QdrantMemoryStore: V1 uses probe limit×10 + client-side `MemoryFilter.matchesMetadata()`; server-side `setFilter()` pushdown when Qdrant proto stabilizes (interface unchanged)

- **Time-driven decay & merge (V1)**

  - `MemoryDecayPolicy.exponential(halfLife)`: assigns 0–1 freshness factor based on `lastAccessedAt`; search results re-ranked by factor; memories below `pruneThreshold()` (default 1%) are hidden; `get()` refreshes `lastAccessedAt` ("access extends lifespan")

  - `MemoryMergePolicy.keyMerge()`: same-key rewrites keep the longer value, union metadata, preserve earliest `createdAt`, refresh `lastAccessedAt`, prefer incoming embedding

  - Both are `@FunctionalInterface` with `NONE` defaults — zero upgrade friction

- **Context-window compaction (V1)** — `MemoryCompactionPolicy` + `manager.compact(ns)`: 100 rounds of conversation → 100 fragments won't fit in your LLM context; group by `metadata.category` and replace each group with a single summarized record
  - Built-in `MemoryCompactionPolicy.categoryGroup()`: pure-Java concatenation (no LLM, deterministic)
  - `langmem4j-strategy` module ships `LlmSummarizationCompaction`: LangChain4j `ChatModel`-driven summarization, or plug any `Function<String,String>` summarizer (Spring AI, custom HTTP)
  - Compacted records carry `compacted=true`, preserve earliest `createdAt`, refresh `lastAccessedAt`
  - **Requires a backend with `listKeys()` support** (InMemory / Qdrant OK; the langgraph4j adapter does not implement it)

- **Zero-dependency runtime** — core / tools-core modules only need `slf4j-api`; Qdrant adapter pulls in the official gRPC client; langgraph4j adapter only needs `langgraph4j-core`

- **Batteries included** — built-in `InMemoryMemoryStore` for tests and prototyping, no external infra needed

- **Framework-decoupled tool layer** — LLM business semantics (save/search/get/list/delete with string I/O, `key=value` metadata parsing, limit clamp) live in `langmem4j-tools-core` (pure Java); `langmem4j-tools` is a 1-line `@Tool`/`@P` wrapper for LangChain4j — switch to Spring AI / custom RPC without rewriting business logic

- **langgraph4j Store SPI adapter** (`langmem4j-langgraph4j`): use any `org.bsc.langgraph4j.store.Store` (InMemoryStore, RedisStore, …) as a langMem4j backend; decay / merge / MemoryFilter all work at the MemoryManager layer

***

## 🧩 Module Structure

```
langmem4j/
├── langmem4j-core              ← required: main dependency
│   ├── memory/Memory.java                 // immutable record (7 fields: ns/key/value/metadata/embedding/createdAt/lastAccessedAt)
│   ├── memory/MemoryDecayPolicy.java      // SPI: decayFactor(createdAt,lastAccessedAt,now) + NONE + exponential(halfLife) + pruneThreshold()
│   ├── memory/MemoryMergePolicy.java      // SPI: merge(existing,incoming) + NONE + keyMerge()
│   ├── memory/MemoryCompactionPolicy.java // SPI: compact(ns,candidates) + NONE + categoryGroup()
│   ├── embedding/EmbeddingGenerator.java  // embedding SPI
│   ├── store/MemoryStore.java             // storage SPI (search includes MemoryFilter)
│   ├── store/MemoryFilter.java            // metadata(AND) + minScore filter
│   ├── store/inmemory/InMemoryMemoryStore.java
│   └── manager/MemoryManager.java         // ✅ the facade you call every day (withDecayPolicy() / withMergePolicy() / withCompactionPolicy() + compact())
│
├── langmem4j-store-qdrant       ← optional: Qdrant vector DB adapter
│   └── (collection = namespace, payload flattening, filter V1 probe 10× + client-side)
│
├── langmem4j-langgraph4j        ← optional: langgraph4j Store SPI adapter
│   ├── LangGraph4jStoreAdapter          // Store → MemoryStore bidirectional mapping (upsert/get/delete/search)
│   └── StoreDecayMergeDemo              // E2E: decay re-ranking + keyMerge + UOE boundaries (runnable main)
│
├── langmem4j-strategy           ← optional: LLM-driven strategy implementations (needs langchain4j-core)
│   └── LlmSummarizationCompaction       // MemoryCompactionPolicy backed by ChatModel or any Function<String,String>
│
├── langmem4j-tools-core         ← optional: framework-free LLM service layer
│   ├── SaveMemoryService        // save / saveWithMetadata / delete
│   └── SearchMemoryService      // get / search + metadataFilter / list
│
├── langmem4j-tools              ← optional: LangChain4j @Tool thin wrapper
│   ├── SaveMemoryTool           // 1:1 delegate to SaveMemoryService
│   └── SearchMemoryTool         // 1:1 delegate to SearchMemoryService
│
└── langmem4j-examples/
    └── langmem4j-example-plain
        ├── PlainExample               // minimal: MemoryManager + tools-core dual paths
        └── ConversationMemoryDemo     // 10-round conversation: merge + decay + filter full chain
    └── langmem4j-example-springboot  // ⚠️ skeleton only (pom, not yet implemented)
```

> **Spring Boot example status**: only an empty pom.xml module exists — no runnable `@AiService` or AutoConfig code yet. See `example-plain` for core capabilities. Full example coming in 0.2.0.

***

## ⚡ Quick Start

### 0. Dependencies (Maven)

```xml
<!-- Required: core includes InMemoryMemoryStore + MemoryManager + MemoryFilter -->
<dependency>
    <groupId>com.langmem4j</groupId>
    <artifactId>langmem4j-core</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>

<!-- Optional: framework-free tool service layer (recommended for Spring AI / standalone) -->
<dependency>
    <groupId>com.langmem4j</groupId>
    <artifactId>langmem4j-tools-core</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>

<!-- Optional: LangChain4j @Tool annotation layer. Use tools-core directly for Spring AI. -->
<dependency>
    <groupId>com.langmem4j</groupId>
    <artifactId>langmem4j-tools</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>

<!-- Optional: Qdrant backend -->
<dependency>
    <groupId>com.langmem4j</groupId>
    <artifactId>langmem4j-store-qdrant</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>

<!-- Optional: langgraph4j Store SPI backend (version managed in parent POM) -->
<dependency>
    <groupId>com.langmem4j</groupId>
    <artifactId>langmem4j-langgraph4j</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>

<!-- Optional: LLM-driven compaction (LlmSummarizationCompaction) -->
<dependency>
    <groupId>com.langmem4j</groupId>
    <artifactId>langmem4j-strategy</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### 1. 3 Lines to Run (InMemory + Metadata Filter + Decay / Merge)

```java
MemoryManager manager = MemoryManager.inMemory()
        .withDefaultNamespace("user_alice")
        .withDecayPolicy(MemoryDecayPolicy.exponential())   // 7-day half-life; results re-ranked by freshness
        .withMergePolicy(MemoryMergePolicy.keyMerge())       // same-key rewrites: keep longer value + union metadata
        .build();

manager.add("food",  "Alice loves hot pot",       Map.of("category", "preference", "source", "chat"));
manager.add("friend","Alice goes hiking with Bob", Map.of("category", "social",    "source", "chat"));
manager.add("work",  "Alice works at Acme as SRE", Map.of("category", "work",      "source", "email"));

// Plain substring search
List<Memory> allHotpot = manager.search("Alice", 10);

// Search only in category=preference: no matter how relevant others are, filter wins
List<Memory> pref = manager.search("Alice", 10,
        MemoryFilter.builder().metadata("category", "preference").build());
// pref only contains "Alice loves hot pot"
```

### 2. Qdrant in 5 Lines (V1: probe limit×10 + client-side filtering)

```java
// 1. Embedding generator
EmbeddingGenerator embedder = text ->
        openAiEmbeddingModel.embed(text).content().vector();

// 2. Qdrant store (gRPC port 6334; supports apiKey/useTls/client injection)
MemoryStore qdrant = QdrantMemoryStore.builder()
        .host("localhost")
        .port(6334)
        .embeddingGenerator(embedder)
        .vectorSize(1536)
        .build();
// Need Qdrant Cloud / API Key?
//   .useTls(true).apiKey("your-api-key")
// Already have a gRPC client instance?
//   .client(existingQdrantClient)

// 3. Wire into MemoryManager
MemoryManager manager = MemoryManager.withStore(qdrant)
        .withEmbeddingGenerator(embedder)
        .withDefaultNamespace("user_alice")
        .build();

manager.add("food", "Alice loves hot pot", Map.of("category", "preference"));

// MemoryFilter semantics: metadata(AND) + minScore enforced at store level
// - InMemoryMemoryStore: filter candidates before ranking (millisecond, no network)
// - QdrantMemoryStore: V1 uses probe limit(×10, capped 500) + client-side match
//   to guarantee MemoryFilter.matchesMetadata() 100% semantic consistency;
//   server-side setFilter pushdown when Qdrant proto Filter/Match API stabilizes
List<Memory> r = manager.search("spicy food", 5,
        MemoryFilter.builder()
                .metadata("category", "preference")
                .minScore(0.6f)
                .build());
```

Start Qdrant:

```bash
docker run -p 6333:6333 -p 6334:6334 qdrant/qdrant
```

### 3. Use langgraph4j Store as Backend (+ Decay / Merge)

Already using `org.bsc.langgraph4j` for agent state machines? Wrap any `Store` with `LangGraph4jStoreAdapter` — MemoryManager handles decay, merge, and MemoryFilter on top:

```java
// 1. Create any langgraph4j Store
Store langgraphStore = new InMemoryStore();

// 2. Wrap as langMem4j MemoryStore
MemoryStore store = new LangGraph4jStoreAdapter(langgraphStore);

// 3. Enable decay + merge (two lines)
MemoryManager manager = MemoryManager.withStore(store)
        .withDefaultNamespace("user_alice")
        .withDecayPolicy(MemoryDecayPolicy.exponential())   // 7-day half-life
        .withMergePolicy(MemoryMergePolicy.keyMerge())       // same-key: keep longer value + union metadata
        .build();

// add()/search()/get()/remove() work exactly like any other backend:
//   · add("pref", "Alice likes hot pot") twice → keyMerge() merges into one
//   · search() auto re-ranks by decay factor; stale memories sink; dead ones (factor<1%) pruned
//   · get() refreshes lastAccessedAt → "access extends lifespan"
```

End-to-end demo (2-second half-life, Thread.sleep 2000 for visible decay re-ranking):

```bash
mvn -pl langmem4j-langgraph4j -am install -DskipTests -q
mvn -pl langmem4j-langgraph4j org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
    -Dexec.mainClass=com.langmem4j.store.langgraph4j.StoreDecayMergeDemo
```

> **listKeys / clearNamespace**: langgraph4j's Store SPI doesn't expose these methods, so `LangGraph4jStoreAdapter` throws `UnsupportedOperationException` (verified in demo). To clear data, delete known keys individually, or wait for langgraph4j API upgrades.

### 4. Expose to LLM (pick one)

#### Option A: LangChain4j @Tool (zero glue code)

```java
var store = manager.store();
var tools = List.of(
        new SaveMemoryTool(store, "user_alice"),
        new SearchMemoryTool(store, "user_alice")
);
Assistant assistant = AiServices.builder(Assistant.class)
        .chatLanguageModel(model)
        .tools(tools)
        .build();

assistant.chat("I'm Alice, I love hot pot. Remember that for next time.");
// → LLM internally calls save_memory(key="favorite_food", content="Alice loves hot pot")
```

#### Option B: No LangChain4j — use tools-core directly (Spring AI / custom bridge)

```java
SaveMemoryService  save   = new SaveMemoryService(manager.store(), "user_alice");
SearchMemoryService search = new SearchMemoryService(manager.store(), "user_alice");

// Same I/O contract as the @Tool version — wire to your own @JsonSchema + function call
String ok   = save.saveMemoryWithMetadata("food", "Alice likes hot pot", "source=user,category=preference");
String hits = search.searchMemory("food", 5, "category=preference");
```

***

## 🧠 Decay, Merge & Compaction Strategies

langMem4j memories aren't an append-only log — three SPIs handle **time-driven freshness**, **same-key merging**, and **fragment→summary compaction** so you don't roll your own eviction/dedup logic.

### Decay: memories age, but aren't deleted

```java
MemoryManager manager = MemoryManager.inMemory()
        .withDefaultNamespace("user_alice")
        .withDecayPolicy(MemoryDecayPolicy.exponential())  // default 7-day half-life
        .build();

// A memory written 7 days ago → factor ≈ 0.5 (one half-life)
// A memory written 46 days ago → factor < 0.01 → auto-hidden by pruneThreshold (not deleted, just not returned)
// get("old_key") hit → refreshes lastAccessedAt → decay clock restarts from "now"
```

| Parameter                 | Default                  | Description                                                       |
| ------------------------- | ------------------------ | ----------------------------------------------------------------- |
| `exponential(halfLifeMs)` | `exponential()` = 7 days | Half-life: factor halves every halfLife period                    |
| `pruneThreshold()`        | `0.01f` (1%)             | Memories below this are hidden from search results; override-able |
| `NONE`                    | —                        | No decay (default, zero upgrade friction)                         |

**Algorithm**: `factor = Math.pow(0.5, (now - lastAccessedAt) / halfLifeMs)` — based on `lastAccessedAt` (not `createdAt`), so a single `get()` "extends lifespan".

### Merge: same-key rewrites auto-merge

```java
MemoryManager manager = MemoryManager.inMemory()
        .withDefaultNamespace("user_alice")
        .withMergePolicy(MemoryMergePolicy.keyMerge())
        .build();

manager.add("food", "Alice likes hot pot",
        Map.of("source", "user", "round", 1));
// 2 seconds later, the user adds more detail:
manager.add("food", "Alice likes spicy hot pot with sesame sauce",
        Map.of("source", "diary", "round", 2, "updated", true));

// After keyMerge, the stored memory is:
//   value  = "Alice likes spicy hot pot with sesame sauce"  ← longer wins
//   meta   = {source=diary, round=2, updated=true}           ← union (incoming overrides same keys)
//   created= round 1's timestamp                             ← earliest preserved
//   last   = round 2's timestamp                             ← refreshed to now
```

| Strategy     | Behavior                                                                                                                               |
| ------------ | -------------------------------------------------------------------------------------------------------------------------------------- |
| `keyMerge()` | Longer value wins · union metadata (incoming overrides) · preserve earliest createdAt · lastAccessedAt=now · prefer incoming embedding |
| `NONE`       | Direct overwrite (default, zero upgrade friction)                                                                                      |
| Custom       | Implement the `MemoryMergePolicy` functional interface                                                                                 |

### Compaction: 100 fragments → a handful of summaries

10 rounds of conversation → 6 memories is fine. 100 rounds → 100 fragments won't fit in your LLM context window. `compact()` groups by `metadata.category` and replaces each group with one summary record:

```java
// Option A: pure Java, no LLM — deterministic concatenation ("v1; v2; v3")
MemoryManager manager = MemoryManager.inMemory()
        .withDefaultNamespace("user_alice")
        .withCompactionPolicy(MemoryCompactionPolicy.categoryGroup())
        .build();

// Option B: LLM-driven — LangChain4j ChatModel summarizes each group
MemoryManager manager = MemoryManager.withStore(store)
        .withDefaultNamespace("user_alice")
        .withCompactionPolicy(new LlmSummarizationCompaction(myChatModel))  // langmem4j-strategy
        .build();

// ... after 50 rounds of conversation:
manager.compact("user_alice");   // 50 fragments → ~5 summarized memories
```

**Compaction rules** (both built-in policies):

- Groups by `metadata.get("category")` (defaults to `"default"` when absent)
- Single-element groups are returned as-is — no wasted LLM call
- Compacted key = `category + "_compacted"`; metadata gains `compacted=true`
- Earliest `createdAt` preserved; `lastAccessedAt` refreshed to now
- Old fragments are deleted; embeddings re-generated if a generator is configured

| Policy                        | Backend        | Behavior                                                     |
| ----------------------------- | -------------- | ------------------------------------------------------------ |
| `categoryGroup()`             | core (pure Java) | Concatenate values with `"; "` — deterministic, no LLM call |
| `LlmSummarizationCompaction`  | strategy (LangChain4j `ChatModel`) | One concise factual summary per group; also accepts any `Function<String,String>` summarizer |
| `NONE`                        | —              | No compaction (default, zero upgrade friction)               |

> ⚠️ **`compact()` requires a `MemoryStore` implementation that supports `listKeys()`** — InMemoryMemoryStore and QdrantMemoryStore are fine; `LangGraph4jStoreAdapter` throws `UnsupportedOperationException` (the langgraph4j Store SPI has no list method).

### Custom Strategy (two lines)

```java
// Example: only decay on weekdays; weekends are "memory-freeze"
MemoryDecayPolicy weekendAware = (createdAt, lastAccessedAt, now) -> {
    java.time.DayOfWeek dow = java.time.DayOfWeek.from(
            java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()));
    return (dow == java.time.DayOfWeek.SATURDAY || dow == java.time.DayOfWeek.SUNDAY)
            ? 1.0f  // weekend: no decay
            : MemoryDecayPolicy.exponential().decayFactor(createdAt, lastAccessedAt, now);
};
```

### Two Runnable Demos

| Demo                       | Module                    | What it verifies                                                                              | Run command                                                                                                                                                                                                                                                  |
| -------------------------- | ------------------------- | --------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **StoreDecayMergeDemo**    | `langmem4j-langgraph4j`   | langgraph4j Store adapter + 2s half-life decay re-ranking + keyMerge + UOE boundaries         | `mvn -pl langmem4j-langgraph4j -am install -DskipTests -q` then `mvn -pl langmem4j-langgraph4j org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.langmem4j.store.langgraph4j.StoreDecayMergeDemo`                                          |
| **ConversationMemoryDemo** | `langmem4j-example-plain` | 10-round conversation → 5 keyMerges → decay ranking (factor table) → MemoryFilter+decay combo | `mvn -pl langmem4j-examples/langmem4j-example-plain -am install -DskipTests -q` then `mvn -pl langmem4j-examples/langmem4j-example-plain org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.langmem4j.example.plain.ConversationMemoryDemo` |

> **ConversationMemoryDemo** output preview (actual run):
>
> ```
> search('Alice', 20) → 6 results
>   key               factor   age(ms)  value
>   user_food       0.9972        8    Alice likes spicy hot pot with extra chili…
>   user_pet        0.8339      524    Alice has a cat named Luna
>   user_location   0.5852     1546    Alice lives in Shanghai
>   user_hobby      0.2911     3561    Alice plays guitar on weekends
>   user_job        0.2447     4062    Alice works as a software engineer at Acme
>   user_name       0.2054     4567    Alice
> ✅ user_food ranks #1 (merge refreshed lastAccessedAt) → factor=0.9972
> ✅ user_name ranks last (round 1, oldest) → factor=0.2054
> ```

***

## 🆚 vs Mem0 / mem4j

| <br />            | langMem4j                                                                                                                                                   | Mem0 / mem4j                                                                            |
| ----------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------- |
| **Language**      | Native Java 17+, bytecode-level interop with Spring / LangChain4j / langgraph4j                                                                             | Python-first; Java side typically via REST + re-serialization                           |
| **Design**        | 5 SPIs (`MemoryStore` / `EmbeddingGenerator` / `MemoryDecayPolicy` / `MemoryMergePolicy` / `MemoryCompactionPolicy`) + 1 facade; **core + tools-core: zero framework deps at runtime** | Monolithic SDK, tightly coupled to specific Vector DB / LLM provider; decay usually DIY |
| **Entry**         | `MemoryManager.add()` — one line generates embedding + writes; search includes metadata filter / decay re-ranking / key-merge                               | Manually compose `Memory + Embedding + Store` objects; write your own eviction          |
| **Tool layer**    | LangChain4j module + pure-Java `tools-core`; switch Spring AI / langgraph4j / custom bridge without rewriting                                               | Bundled with the official framework only                                                |
| **Test-friendly** | `InMemoryMemoryStore` / `langgraph4j InMemoryStore` out-of-box; 160+ unit tests in milliseconds                                                             | Often needs containers or mocking the entire client layer                               |

Bottom line: **langMem4j is the "just enough" memory layer for Java developers — SPI + facade + time-driven strategies (decay / merge) + multi-backend adapters, without locking you into any LLM or DB.**

***

## 🧪 Build & Test

```bash
# All module unit tests (<15s, Qdrant integration tests @Disabled)
mvn test

# Package all modules / install to local repo (skip tests)
mvn install -DskipTests

# Run minimal example (PlainExample — MemoryManager + tools-core dual paths)
mvn -pl langmem4j-examples/langmem4j-example-plain -am package -DskipTests -q
mvn -pl langmem4j-examples/langmem4j-example-plain \
    org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
    -Dexec.mainClass=com.langmem4j.example.plain.PlainExample

# Run conversation demo (ConversationMemoryDemo — 10 rounds + merge + decay ranking, ~5s)
mvn -pl langmem4j-examples/langmem4j-example-plain \
    org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
    -Dexec.mainClass=com.langmem4j.example.plain.ConversationMemoryDemo

# Start Qdrant, then enable integration tests (remove @Disabled on the class)
#   See langmem4j-store-qdrant/src/test/java/.../QdrantMemoryStoreIntegrationTest.java
#   docker run -d -p 6333:6333 -p 6334:6334 qdrant/qdrant
```

Test matrix (`mvn test`, **165 tests all green ✅**, plus 4 `@Disabled` reserved methods):

| Module                    | Tests                                   | Coverage                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| ------------------------- | --------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| langmem4j-core            | **113 passed**                          | Memory (10) · InMemoryMemoryStore (18) · CosineSearch (7) · **MemoryManager (29: +5 compact — NONE noop / categoryGroup merge / old-keys deleted / empty ns noop / earliest createdAt; +9 decay/merge — decay filter & re-ranking / get refresh / merge longer & union / addAll merge)** · **MemoryFilter** (11) · **InMemoryMemoryStore-Filter** (5) · **MemoryDecayPolicy (11: NONE identity / half-life curve / lastAccessedAt over createdAt / custom halfLife / pruneThreshold override)** · **MemoryMergePolicy (10: NONE identity / longer value / metadata union / earliest createdAt / incoming-embedding / no input mutation)** · **MemoryCompactionPolicy (12: NONE identity / single-element skip / concatenation / metadata preserved / earliest createdAt / multi-group output / default group / empty list / no mutation / lastAccessedAt refreshed)** |
| langmem4j-store-qdrant    | 4 passed **+ 4 @Disabled**              | QdrantMemoryStoreTest: deterministicId pure functions 4 cases (FNV×32/64/utf16-leak/Chinese); 4 integration test methods **@Disabled** (below)                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| **langmem4j-langgraph4j** | **7 passed**                            | LangGraph4jStoreAdapterTest: ①upsert+get round-trip (namespace non-empty / createdAt / embedding null) ②getByKey miss → empty ③deleteByKey → empty ④search keyword match (fruit 2 / blue 1) ⑤search limit=3 ⑥listKeys → UOE ⑦clearNamespace → UOE                                                                                                                                                                                                                                                                                                                                                        |
| **langmem4j-strategy**    | **13 passed**                           | LlmSummarizationCompactionTest: ctor null validation / single-element skips LLM call / multi-group one summary per group / single-in-group stays as-is / category metadata preserved / earliest createdAt / key = category_compacted / no-category default group / empty list / LLM prompt contains original values / identity summarizer / no input mutation / lastAccessedAt refreshed                                                                                                                                                                                                                    |
| langmem4j-tools-core      | **14 passed**                           | SaveMemoryService (6: ctor validation / KV parsing / boolean tags / empty metadata / delete / ns accessor); SearchMemoryService (8: get hit/miss / substring / empty msg / limit clamp 4 boundaries incl >20→20 / **metadata filter search** / list empty+non-empty / accessor)                                                                                                                                                                                                                                                                                                                          |
| langmem4j-tools           | **14 passed**                           | SaveMemoryTool (7, thin-wrapper delegation + **namespace isolation**); SearchMemoryTool (7, thin-wrapper delegation)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| **Total**                 | **165 passed · 4 skipped · 0 failures** | All green ✅                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |

> **Integration test status**: V1 ships `QdrantMemoryStoreIntegrationTest` with `@Disabled` (4 E2E methods: ①upsert+get round-trip (metadata restore) ②search cosine ranking ③**search + MemoryFilter** (category=drink exact hit) ④delete + listKeys cleanup). To run locally:
>
> ```bash
> docker run -d -p 6333:6333 -p 6334:6334 qdrant/qdrant
> # Remove @Disabled from QdrantMemoryStoreIntegrationTest.java, then:
> mvn test -pl langmem4j-store-qdrant -am -Dtest=QdrantMemoryStoreIntegrationTest
> ```

***

## 🗺️ Roadmap

- [x] Core SPI + InMemory implementation + MemoryManager facade

- [x] **MemoryFilter (metadata + minScore)** SPI-level support + InMemory/Qdrant

- [x] **MemoryDecayPolicy (exponential half-life + pruneThreshold override + get() refresh)** + **MemoryMergePolicy (keyMerge / NONE default)**

- [x] Qdrant adapter (V1: probe limit×10 + client-side `MemoryFilter.matchesMetadata()`; 100% SPI semantic consistency; server-side `setFilter` pushdown when proto stabilizes) + integration test placeholder

- [x] **langgraph4j Store adapter** (`langmem4j-langgraph4j`): any `org.bsc.langgraph4j.store.Store` as backend; decay / merge / MemoryFilter at facade layer

- [x] **langmem4j-tools-core (pure Java)** + **langmem4j-tools (LangChain4j)** layering

- [x] **MemoryCompactionPolicy** (`compact()` manual trigger) + `langmem4j-strategy` module with `LlmSummarizationCompaction` (ChatModel or custom `Function` summarizer); auto-trigger on size threshold deferred to V2 (concurrency + search-consistency concerns)

- [x] Plain Java examples (example-plain: PlainExample + ConversationMemoryDemo)

- [ ] Spring Boot example (`example-springboot` is still a pom skeleton)

- [ ] Spring Boot Starter: `@EnableLangMem4j` auto-configuration

- [ ] Milvus / PGVector adapters (waiting for first real user demand)

- [ ] **Semantic-level dedup (currently only keyMerge; true semantic dedup needs cosine similarity threshold)**

- [ ] Memory Evolve: periodic LLM-driven merge / evict (Functional Core, stateless)

- [ ] Compaction auto-trigger (size-threshold based) + compaction on langgraph4j backend (blocked on upstream listKeys API)

***

## 📜 License

MIT © langMem4j Contributors
