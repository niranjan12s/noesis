# Noesis

Extracts `(subject, predicate, object)` triples from Markdown using an LLM, deduplicates them into a PostgreSQL knowledge graph, indexes edges in OpenSearch, and exposes a hybrid BM25 + graph traversal retrieval API.

---

## 1. What Problem This Solves

Chunk-based vector RAG breaks down in two scenarios:

- **Multi-hop queries** — "What services depend on the database the billing module writes to?" requires traversing connections across documents. Vector similarity has no edge model; it retrieves isolated chunks and relies on the LLM to synthesize the path.
- **Explainability** — a vector match returns a similarity score and a chunk. There is no way to trace *why* two facts are related or what document chain produced them.

Noesis stores typed edges between named entities at ingestion time. Queries traverse those edges deterministically, and every result links back to a specific assertion, chunk, and source document.

## 2. System Approach

```
File → Chunk → LLM → (S,P,O) → Dedup + Persist (Postgres + OS) → QUERYABLE
```

**Chunking.** Markdown is split into fixed-size windows with overlap. Each chunk is processed independently.

**LLM extraction.** Each chunk is sent to a configurable provider (Groq, Gemini, Ollama, or any REST API). The LLM returns zero or more `(subject, predicate, object)` triples. The predicate must match one of ~150 canonical entries (e.g., `DEPENDS`, `WRITES`). Near-misses (Levenshtein distance ≤ 2) are auto-corrected. Unrecognized predicates go to a human review queue.

**Graph persistence.** Triples are deduplicated by SHA-256 of their canonical text. Nodes and edges are stored in PostgreSQL. Edges are indexed in OpenSearch for BM25 entry-point lookup.

**Retrieval.** BM25 over edge text finds entry nodes. BFS over PostgreSQL edges collects connected nodes up to a configurable depth. Results include source chunk and document for every assertion.

**Replayability.** Every ingestion run is a UUID-tracked unit. All state transitions (chunking, extraction, graph build) are persisted to an event log (Kafka in Bulk mode, JPA records in Real-Time mode). This means:
- A failed extraction retries without re-chunking the document.
- A corrupted graph index rebuilds from stored assertions without re-invoking the LLM.
- On restart, `DocumentRecoveryService` resumes non-terminal documents from the last completed stage.
- In Bulk mode, workers consume from the event log offset without coordinating recovery with each other.

The event log is append-only. No compaction or retention enforcement. Long-running deployments require external retention management.

## 3. Why This Design Was Chosen

| Decision | Problem | Approach | Tradeoff |
|---|---|---|---|
| Graph extraction | Vector similarity can't model relationships | LLM extracts typed edges at ingestion time | High per-document cost and latency. Brittle for unstructured text. In exchange: every fact is traceable, multi-hop queries use deterministic BFS. |
| Kafka durability | Pipeline state must survive process crashes | Append-only event log. Stages can replay without re-ingesting source. | Kafka must be running. Real-Time mode publishes to Kafka but processes in-memory — a crash between publish and processing loses that state. |
| Redis coordination | No central scheduler for Bulk workers | Heartbeat registry + consistent hashing for path assignment. Distributed locks prevent duplicate processing. | Stale workers go undetected until heartbeat timeout. Crashed workers hold locks until TTL expiry. Simpler than a central orchestrator but less deterministic. |
| Deterministic traversal | Results must be auditable | BFS over exact edges in PostgreSQL | Fuzzy semantic queries may miss relevant nodes with different terminology. No dense embedding fallback. |
| Human predicate review | LLM hallucinates invalid predicates | Unrecognized predicates queued for operator approval. Levenshtein auto-correct catches misspellings first. | Creates an operational queue. High ingestion volume can overwhelm reviewers. |

## 4. Operational Characteristics

### Failure handling

- **LLM call failure** — retried with exponential backoff up to a configured maximum. After exhaustion the document enters `FAILED_FATAL` and stops.
- **Database write failure** — transaction rolls back. The document retries on the next scheduler tick.
- **Kafka outage** — Real-Time mode continues (local event processing). Bulk mode stops.
- **Redis outage** — API falls back to PostgreSQL. Real-Time mode continues (SQLite tracks state). Bulk mode loses worker coordination.

### Scaling bottlenecks

- **LLM API rate limits** — the most common bottleneck. A concurrency semaphore and optional calls-per-minute limiter provide backpressure.
- **OpenSearch write pressure** — bulk ingestion can saturate OpenSearch on modest hardware.
- **Single-node watcher** — Real-Time mode processes everything on one node. Throughput is bounded by that node's LLM concurrency.

### Cost implications

The dominant cost is LLM inference. Every chunk requires at least one LLM call. At 512 characters per chunk, a 50 KB document produces ~100 chunks. Token costs scale linearly with document volume and chunk count. Using a local Ollama model eliminates API fees but increases per-chunk latency.

### Known limitations

- **Narrative text** — documents without clear entity-relation structure produce sparse graphs. Retrieval degrades to BM25-only.
- **Soft semantic queries** — "find anything related to security" requires exact keyword or entity match for entry. No dense embedding fallback.
- **High update velocity** — every file change triggers full re-ingestion. No incremental diff.
- **Cold start** — the graph is empty until at least one document is fully processed.

## 5. Retrieval Model

1. **BM25 entry** — the query string is matched against edge text in OpenSearch. The top-K matching edges identify candidate entry nodes.
2. **BFS traversal** — from each entry node, traverse outgoing edges in PostgreSQL up to a configurable depth. All visited edges are collected.
3. **Ranking** — results are scored by BM25 relevance (entry point) and traversal depth (shorter paths rank higher).

Traversal depth is bounded because relevance degrades with each hop. Depth 3 captures direct dependencies and one transitive level without flooding the result set.

Semantic similarity still matters at step 1: if the query uses different terminology than any stored edge, BM25 fails to find an entry node and traversal returns nothing. There is no fallback to dense embeddings.

### Concrete example

Query: *"What depends on the payment database?"*

1. BM25 on edge text matches `(PaymentService, WRITES, PaymentDB)`. `PaymentDB` is entered as a node.
2. BFS traverses incoming edges: `(BillingService, READS, PaymentDB)`, `(AuditService, READS, PaymentDB)`, `(ReportingService, DEPENDS, PaymentDB)`.
3. Results sorted by path length:

```
BillingService   READS  PaymentDB   (depth 1, doc: architecture.md)
AuditService     READS  PaymentDB   (depth 1, doc: compliance.md)
ReportingService DEPENDS PaymentDB  (depth 1, doc: operations.md)
```

Each result links to the source document and chunk that produced the assertion. At depth 2, traversal continues from those services to find transitive dependencies.

## 6. Architecture

```
File/Dir → Chunk → LLM → (S,P,O) → Dedup → Postgres + OpenSearch → BM25 → BFS

Modes:  Real-Time (FileWatcher → local pipeline)
        Bulk (Consistent hash → Kafka → worker pool)
Coordination: Redis (locks, registry, dedup cache)
```

## 7. Running the System

### Requirements

- Docker (PostgreSQL, Kafka, OpenSearch, Redis)
- JDK 21 (auto-downloaded by Gradle toolchain)

### Quick start

```
.\noesis-start.bat     # Docker → build JAR → launch on :8081
.\noesis-stop.bat      # stop app → docker-compose down
```

### Configuration

```
python noesis.py setup      # interactive LLM provider config
```

Or from the Settings tab in the dashboard (`http://localhost:8081`). File glob patterns in `.noesis/config.json`:
```json
{ "include": ["**/*.md", "docs/**/*.md"],
  "exclude": ["node_modules/**", ".git/**", "build/**", ".noesis/**"] }
```

### Starting ingestion

- **Real-Time**: create or modify a Markdown file in the project root. The watcher picks it up within ~200 ms.
- **Bulk**: `POST /api/bulk/start {"directory": "/path/to/docs"}` or via the dashboard.

## 8. API Summary

| Endpoint | Purpose |
|---|---|
| `GET /api/documents` | List documents with pipeline status |
| `POST /api/documents/upload` | Upload a file for ingestion |
| `DELETE /api/documents/{id}` | Soft delete (5 min grace window) |
| `POST /api/documents/{id}/hard-delete` | Force delete |
| `GET /api/tools/query_graph?text=...&depth=3` | BM25 → BFS graph query |
| `GET /api/predicates/active` | List canonical predicates |
| `GET /api/predicates/failed` | Predicates pending human review |
| `POST /api/predicates/approve?name=...` | Approve a failed predicate |
| `GET /api/llm/config` | Current LLM provider and settings |
| `PUT /api/llm/config` | Update LLM config at runtime |
| `GET /api/bulk/start` | Start bulk ingestion job |
| `GET /actuator/health` | System health |

Full API documentation: `docs/api.md`.

## 9. Limitations

- **LLM extraction is lossy.** The LLM may hallucinate predicates, miss entities, or produce malformed triples. The auto-correct step catches misspellings but cannot fix semantically incorrect assertions.
- **Ingestion is slow and expensive.** Every chunk triggers an LLM call. A 100 KB document can cost $0.05–$0.50 in API fees and take 30–120 seconds to process.
- **Graph quality depends on source text quality.** Documents without clear entity-relation structure produce sparse or incorrect graphs. Implicit relationships are not extracted.
- **No semantic fallback.** Queries that fail BM25 entry-point lookup return empty results. No dense embedding second pass exists.
- **Bulk mode requires Kafka and Redis.** Real-Time mode works without them, but Bulk mode fails if either is unavailable.
- **No incremental updates.** Any file change triggers full re-ingestion. No diff-based update for individual assertions.
- **Operator burden.** The predicate review queue requires human attention. Unrecognized predicates can accumulate faster than operators can review them.
