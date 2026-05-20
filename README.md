# Neosis — Semantic Graph Memory System

Neosis ingests Markdown documentation, extracts semantic assertions via an LLM, builds a deduplicated knowledge graph in PostgreSQL, indexes it in OpenSearch, and exposes a query API for graph traversal — plus a real-time dashboard with predicate curation and a force-directed graph explorer.

Neosis operates in two distinct operational modes: **Real-Time Mode** for instant watcher-driven ingestion, and **Bulk Indexing Mode** for high-throughput, distributed batch ingestion across multiple dynamic worker instances.

---

## System Architecture & Ingestion Modes

Neosis supports two architectural execution paths tailored for active workspace development and distributed bulk ingestion.

```
                  ┌─────────────────────────────────────────┐
                  │          File Event Detected            │
                  └────────────────────┬────────────────────┘
                                       │
                    Is Bulk Mode & Active Job Running?
                                       ├─── No ──► [Real-Time Mode] (Synchronous Virtual Threads)
                                       │
                                      Yes
                                       ▼
                             [Bulk Indexing Mode]
                         (Distributed Kafka Pipeline)
```

### 1. Real-Time Mode (Default)
Driven synchronously by Spring `ApplicationEventPublisher` + `@EventListener` on virtual threads. Every document pipeline run is allocated to an isolated, lightweight Virtual Thread.

* **Durability-Only Kafka**: Kafka is configured in **publish-only** mode for persistence and replay durability. The system registers event topics, but the consumer groups are no-ops; processing is orchestrated in-memory to keep developer cycles instant.
* **Orchestration Flow**:
  ```
  FileWatcherService ──debounce──► DocumentIngestionService
                                        │
                                PipelineEvent(DOCUMENT_CREATED)
                                        │
                                LocalDocumentPipelineListener
                                        │
                                MarkdownChunkingService ──► PostgreSQL (chunks)
                                        │
                                PipelineEvent(CHUNKING_COMPLETED)
                                        │
                                AssertionExtractionService ──► Groq/Gemini API (LLM)
                                        │
                                PipelineEvent(ASSERTION_EXTRACTION_COMPLETED)
                                        │
                                GraphComponentService
                                    ├─ Deduplicate nodes via SHA-256 checksum
                                    ├─ Deduplicate edges via SHA-256 checksum
                                    ├─ Bulk index assertions + edges to OpenSearch
                                    └─ Mark document QUERYABLE
  ```
* **Local State Store**: SQLite (`.neosis/state.db`) tracks localized pipeline state and per-document progress.

### 2. Bulk Indexing Mode (Distributed)
Optimized for high-throughput initial indexing or massive workspace migrations. It suspends user modifications and query endpoints to prioritize 100% of network, database, and CPU bandwidth to ingestion.

* **Orchestration Flow**:
  ```
  [BulkFileWatcher] 
         │
    (Walks directory, evaluates Consistent Hash Ownership)
         │
  [MarkdownChunkingService] ──► [PostgreSQL] (Chunks saved)
         │
    (Produces ChunkCreatedEvent to 'chunk.created.events')
         │
   ┌─────┴───────────────────────────────────────────────────────┐
   │                   sgms-bulk-assertion-group                 │
   └─────────────────────────────┬───────────────────────────────┘
                                 ▼
                      [BulkAssertionConsumer]
                      ├─ Concurrency limit via Semaphore (default 8)
                      ├─ Distributed lock: neosis:lock:assertion:<documentId>
                      └─ [AssertionExtractionService] ──► [PostgreSQL] (Assertions saved)
                                 │
                           (Produces AssertionGeneratedEvent to 'assertion.generated.events')
                                 │
   ┌─────────────────────────────┴───────────────────────────────┐
   │                     sgms-bulk-graph-group                   │
   └─────────────────────────────┬───────────────────────────────┘
                                 ▼
                        [BulkGraphConsumer]
                        ├─ Distributed lock: neosis:lock:graph:<ingestionRunId>
                        └─ [GraphComponentService]
                           ├─ Deduplicates nodes and edges via SHA-256
                           ├─ Asynchronously indexes batch to OpenSearch
                           └─ Marks document QUERYABLE
  ```
* **Query Freezing**: During active bulk job execution, the read/traversal APIs (`/api/tools/query_graph`) are suspended to guarantee undivided resource allocation.
* **Read-Only Dashboard**: The frontend UI transitions to a read-only state, disabling predicate manual curation, node deletions, or manual ingestion requests.
* **OpenSearch High-Density Writes**: Assertions and edges are queued and bulk-indexed into OpenSearch asynchronously. OpenSearch refresh intervals are delayed dynamically to avoid segment thrashing (governed by `sgms.bulk.osRefreshIntervalSeconds`), and payloads are constrained by bytes (up to `sgms.bulk.osBulkPayloadMaxBytes`).
* **SSE-Driven Metrics**: Live metrics are aggregated by `BulkProgressStore` in Redis and streamed via Server-Sent Events (SSE) (`/api/bulk/progress`) to the dashboard, including:
  - Files Discovered, Processed, and Failed.
  - Assertions and Edges Generated.
  - Backlog queues and active JVM worker memory footprints.
  - Processing Throughput (documents/sec) and real-time ETA.

### Operations Modes Comparison

| Feature | Real-Time Mode | Bulk Indexing Mode |
|---|---|---|
| **Primary Driver** | Watcher debounced local events | Distributed Kafka consumer groups |
| **Concurrency Model** | In-process Java 21 Virtual Threads | Distributed Workers + Semaphore limits |
| **Query API Status** | Fully active and queryable | Suspended (frozen to prioritize writes) |
| **UI Operations** | Read-Write (Curation, deletes active) | Read-only UI state during active execution |
| **Progress Persistence**| SQLite (`.neosis/state.db`) | Redis state tracking + SSE Metrics |
| **OpenSearch Batching** | Per-document updates | High-density bulk operations |
| **Horizontal Scaling** | Single-instance watcher | Horizontal worker pools (Consistent Hashing) |

---

## Distributed Architecture & Dynamic Worker Scaling

Neosis handles massive datasets by executing in a horizontally-scalable, distributed multi-worker configuration. Multiple worker instances can run concurrently, sharing a PostgreSQL database, a Redis cache, and a Kafka cluster.

### 1. Worker Registration & Metadata
At startup, each worker node registers itself in the Redis-backed Worker Registry managed by `WorkerRegistryService`.
* **Worker ID**: An 8-character unique alphanumeric identifier derived from a random UUID.
* **Instance Name**: Constructed combining host name and worker ID (e.g., `hostname-a7c8f2b1`).
* **Active Status**: Registered workers continuously track and expose:
  - **Worker ID & Instance Name**: System identifiers.
  - **Uptime**: Time elapsed since initialization.
  - **Last Heartbeat**: ISO-8601 timestamp of the last heart-pulse.
  - **Current Load**: Tracking the number of active threads/chunks in progress inside the worker instance.
  - **Completion Throughput**: Performance metrics of documents indexed per second.

### 2. Redis Heartbeats & Consistent Hashing
To coordinate without a single point of failure (SPOF) or a complex leader election system, workers coordinate using Redis heartbeats and consistent hashing.
* **Worker Heartbeats**: Every 5 seconds, a scheduled background thread (`worker-heartbeat`) pushes health packets to Redis with a 15-second TTL:
  - `neosis:worker:<workerId>` -> `Instant.now().toString()`
  - `neosis:worker-info:<workerId>` -> `instanceName|currentLoad|Instant.now().toString()`
* **Path-Based Consistent Hashing**: When a file is discovered or modified, workers dynamically calculate path ownership to prevent duplicate ingestion:
  1. The worker fetches active workers from the registry.
  2. For each active worker, it calculates a hash weight using the MD5 digest:
     $$\text{hash} = \text{MD5}(\text{workerId} + ":" + \text{normalizedFilePath})$$
  3. The worker with the highest long weight becomes the owner of the document path.
  4. Only the owner worker ingest and chunks the document. This avoids duplicate work and eliminates redundant LLM token costs.
* **Stale Worker Eviction**: A scheduled cleanup thread (`worker-check`) runs every 10 seconds. If a worker's heartbeat in `neosis:worker:<workerId>` is older than 15 seconds, the worker registry evicts it, triggering active workers to recalculate consistent hash weights and dynamically take over orphaned files.

---

## Kafka-driven Bulk Ingestion Pipeline

When Bulk Mode is enabled and a job is running, the ingestion flow migrates from local synchronous event paths to distributed, asynchronous Kafka consumer groups.

### 1. Consumers & Groups
* **`BulkAssertionConsumer` (Consumer Group: `sgms-bulk-assertion-group`)**:
  - Listens to `ChunkCreatedEvent` on the topic `chunk.created.events` (constant `KafkaTopics.CHUNK_CREATED_EVENTS`).
  - Throttles execution through a `Semaphore` (concurrency limit of 8 or custom `sgms.bulk.llmConcurrency`) to regulate downstream API calls to LLM providers (Groq/Gemini).
  - Dynamically increments worker active load in the registry, extracts semantic assertions via LLM, saves entities to PostgreSQL, and releases worker load.
* **`BulkGraphConsumer` (Consumer Group: `sgms-bulk-graph-group`)**:
  - Listens to `AssertionGeneratedEvent` on the topic `assertion.generated.events` (constant `KafkaTopics.ASSERTION_GENERATED_EVENTS`).
  - Directs graph compiling, node/edge SHA-256 deduplication, and OpenSearch bulk writes through `GraphComponentService`.

### 2. Redis Distributed Locking
To enforce single-instance processing safety in a multi-instance cluster, Redis-backed distributed locks are acquired with a 30-minute default TTL:
* **Assertion Extraction Lock**: Key `neosis:lock:assertion:<documentId>` ensures a document's chunks are only processed by one worker node at a time.
* **Graph Building Lock**: Key `neosis:lock:graph:<ingestionRunId>` ensures graph node/edge deduplication and index compilation run on exactly one worker instance.

---

## Redis Deduplication & State Tracking Layer

High-throughput bulk ingestion demands bulletproof state tracking. The `RedisDedupService` manages deduplication and state preservation across the entire cluster.

### 1. Deduplication (Checksum Tracking)
* **Redis Key**: `neosis:dedup:checksum:<hash>`
* **Value**: `<documentId>|<timestamp>`
* **TTL**: 24 Hours
* **Workflow**: When `BulkFileWatcher` scans a file, it computes a SHA-256 hash. The `DocumentIngestionService` queries the Redis dedup store. If the checksum is found, the file is immediately skipped:
  ```
  Checksum unchanged (Redis dedup) for document: docs/api.md. Skipping.
  ```
  If not found, the file is ingested, and the checksum is recorded in Redis to prevent any subsequent worker from ingesting it.

### 2. State Tracking (Progress Preservation)
* **Redis Key**: `neosis:dedup:state:<docId>`
* **Value**: `<stage>|<timestamp>` (where `<stage>` corresponds to pipeline stages like `CHUNKING`, `PROCESSING_ASSERTIONS`, `QUERYABLE`)
* **TTL**: 24 Hours
* **Workflow**: As a document traverses the bulk pipeline, its active stage is updated in Redis. If a worker fails mid-job, or if a container restarts, the scheduler checks this state to resume execution exactly where it left off, avoiding redundant LLM compilation for completed stages.

---

## Prerequisites

- **Java 21** — built and tested with Eclipse Adoptium Temurin-21
- **Docker + Docker Compose** — for running PostgreSQL, Apache Kafka, OpenSearch, and Redis
- **Groq or Gemini API key** — for LLM-based assertion extraction
- **~1 GB free RAM** for the Gradle daemon during builds (low-memory systems: use `bootJar -x test --no-daemon`)

---

## Setup

### 1. Start infrastructure

```bash
docker-compose up -d
```

This starts four containers:

| Service | Container name | Port | Purpose |
|---|---|---|---|
| PostgreSQL 15 | `sgms-postgres` | 5432 | Canonical graph store (schema: `sgms`) |
| Apache Kafka (KRaft) | `sgms-kafka` | 9092 | Publish-only durability & asynchronous bulk streams |
| OpenSearch 2.13 | `sgms-opensearch` | 9200 | Query index |
| Redis 7 | `sgms-redis` | 6379 | Distributed caching, heartbeats, lock manager, and dedup store |

Verify health:

```bash
docker ps --format "table {{.Names}}\t{{.Status}}"
```

### 2. Initialize Neosis

```bash
python neosis.py init
```

This creates `.neosis/` with:

| File | Purpose |
|---|---|
| `state.db` | SQLite database tracking pipeline progress per document (used in Real-Time Mode) |
| `config.json` | Glob patterns that control which files are watched |
| `.gitignore` | Prevents `state.db` and retry logs from being committed |
| `cache/` | Working directory for cache |
| `retries/` | Per-document retry traceback logs |

The default include patterns in `.neosis/config.json` are:

```json
{
  "include": ["**/*.md", "docs/**/*.md", "README.md"],
  "exclude": ["node_modules/**", ".git/**", "dist/**", "build/**", ".neosis/**"]
}
```

You can edit `config.json` to add or remove patterns — the service reloads it at startup.

### 3. Configure the LLM provider

The default LLM provider is **Groq** (`llama-3.1-8b-instant`):

```bash
# Windows (PowerShell)
$env:GROQ_API_KEY = "gsk_your_key_here"

# Linux/macOS
export GROQ_API_KEY="gsk_your_key_here"
```

Alternative providers (set `sgms.llm.provider` in `application.yml`):

| Provider | Env var | Model |
|---|---|---|
| `groq` (default) | `GROQ_API_KEY` | `llama-3.1-8b-instant` |
| `gemini` | `GEMINI_API_KEY` | gemini-1.5-flash |
| `ollama` | (none) | `llama3.2:1b` (local) |
| `mock` | (none) | Hardcoded test data |

### 4. Build and start

```bash
./gradlew bootJar -x test
java -jar build/libs/noesis-sgms-1.0.0-SNAPSHOT.jar --server.port=8081 --spring.profiles.active=dev
```

On low-memory systems, use `--no-daemon`:

```bash
./gradlew bootJar -x test --no-daemon
```

Server starts on **port 8081**. Confirm:

```bash
curl http://localhost:8081/actuator/health
```

```json
{"status":"UP","components":{"db":{"status":"UP"},"redis":{"status":"UP"},"diskSpace":{"status":"UP"}}}
```

### 5. Open the dashboard

Navigate to `http://localhost:8081` in a browser. The dashboard has three tabs:

| Tab | Description |
|---|---|
| **Dashboard** | Real-time metrics cards + Chart.js time-series (throughput, latency, error rate) + D3 force-directed knowledge graph |
| **Predicate Curation** | Approve/reject failed predicates; groups predicates into 7 categories with badge colours and hover-card suggestions |
| **Explorer** | Full-screen D3 force-directed graph with zoom/pan; select nodes to see neighbours and traversal paths |

---

## Configuration Properties

Specify these properties in `application.yml` under the `sgms` namespace to customize system settings:

### General & LLM Properties
* `sgms.llm.provider`: `groq` (default), `gemini`, `ollama`, or `mock`.
* `sgms.llm.model`: Model identifier to target (e.g. `llama-3.1-8b-instant` or `gemini-1.5-flash`).
* `sgms.watch.dir`: Watcher target directory (default: `./noesis`).
* `sgms.traversal.max-depth`: Max traversal depth for hybrid BFS graph queries (default: 3).

### Bulk Mode Performance Configurations (`sgms.bulk.*`)

| Property | Default | Description |
|---|---|---|
| `sgms.bulk.chunkBatchSize` | `50` | Number of chunks processed in a single batch. |
| `sgms.bulk.osRefreshIntervalSeconds` | `30` | Interval to delay index refreshes during bulk index jobs to optimize throughput. |
| `sgms.bulk.llmConcurrency` | `8` | Maximum concurrent LLM extraction requests per worker (governed by a Semaphore). |
| `sgms.bulk.pgBatchSize` | `500` | Hibernate JDBC insert batch size for PostgreSQL database writes. |
| `sgms.bulk.edgeBulkBatchSize` | `2000` | Max batch size of nodes/edges for OpenSearch bulk index requests. |
| `sgms.bulk.osBulkPayloadMaxBytes` | `15728640` | Maximum byte payload size (15MB) allowed for a single OpenSearch bulk request. |
| `sgms.bulk.kafkaConsumptionBatchSize` | `200` | Batch size limit for bulk Kafka listeners. |
| `sgms.bulk.heartbeatIntervalSeconds` | `5` | Frequency of heartbeats sent by active worker nodes to Redis. |
| `sgms.bulk.heartbeatTimeoutSeconds` | `15` | Expiration time for worker registry keys in Redis. Workers inactive beyond this are evicted. |
| `sgms.bulk.workerCheckIntervalSeconds` | `10` | Frequency of the stale worker check thread. |

---

## Usage

### Ingesting a document (Real-Time Mode)

Place a Markdown file **anywhere in the repository** (except `node_modules/`, `.git/`, `build/`, `.neosis/`, `logs/`). The file watcher recursively monitors the root directory and automatically detects new or modified files:

```bash
cat > docs/my_service.md << 'EOF'
# My Service

The processing of inbound payment requests is performed by the Transaction Processor.
The validation of transaction data is handled by the Validation Service.
The Transaction Processor calls the Banking Gateway for settlement.
EOF
```

The file is detected within 100ms (new file) or 200ms (modification), and the pipeline runs:

1. **Detection** — `FileWatcherService` debounces the event
2. **Filtering** — `NeosisConfigService` checks the file path against `.neosis/config.json` glob patterns
3. **Chunking** — `MarkdownChunkingService` splits into 512-char windows (64-char overlap)
4. **LLM extraction** — `AssertionExtractionService` sends each chunk to Groq; responses are validated (predicate must match canonical enum, raw text must be grounded in the chunk)
5. **Graph building** — `GraphComponentService` creates deduplicated nodes/edges via SHA-256 checksums
6. **Indexing** — Bulk-index assertions + edges to OpenSearch asynchronously
7. **Completion** — Document marked `QUERYABLE` in PostgreSQL and SQLite

### Ingesting Documents (Bulk Indexing Mode)

To run a massive batch ingestion:

1. **Switch Mode**: Set the system mode to bulk via the API.
2. **Initiate Bulk Scan**: Supply the root directory to ingest. Workers will coordinate consistent hash weights, distribute the workload, chunk content, route events through Kafka topics, and execute high-density writes into PostgreSQL and OpenSearch.
3. **Stream Progress**: Open the dashboard to see live SSE-streamed throughput, queue backlogs, active worker pools, and expected ETA.

#### Monitoring progress via CLI

```bash
python neosis.py status
python neosis.py logs docs/my_service.md
```

The `status` command shows each document's pipeline stage:

```
File Path                Pipeline State         Progress
──────────────────────────────────────────────────────────
docs/my_service.md       QUERYABLE              3/3
README.md                PROCESSING_ASSERTIONS  2/5
```

### Excluding files

The exclude patterns in `.neosis/config.json` take precedence over includes. To exclude a file or directory, add a glob pattern:

```json
{
  "include": ["**/*.md", "docs/**/*.md", "README.md"],
  "exclude": ["node_modules/**", ".git/**", "dist/**", "build/**", ".neosis/**", "archive/**"]
}
```

The watcher also skips the following directories at the OS level (regardless of config): `.git/`, `node_modules/`, `build/`, `.neosis/`.

---

## Pipeline Stages

| Stage | Description |
|---|---|
| `DISCOVERED` | File detected by watcher |
| `CHUNKING` | Document being split into chunks |
| `CHUNKING_COMPLETED` | Chunks written to PostgreSQL |
| `PROCESSING_ASSERTIONS` | LLM extracting assertions per chunk |
| `PROCESSING_GRAPH` | Graph component building (nodes, edges, OS index) |
| `QUERYABLE` | Fully indexed and queryable |
| `RETRYING` | LLM extraction failed, waiting for retry (exponential backoff: 15s, 30s, 60s, 120s, 240s; max 5 retries) |
| `FAILED_FATAL` | All retries exhausted |

---

## Predicate Validation and Curation

### How it works

1. The LLM is prompted with the full list of 121 valid predicates from `PredicateType`.
2. The response is passed through `AssertionValidationService.resolvePredicate()` which normalizes tense (`ED` → stem+`S`, `ING` → stem+`S`, `ES` stripping, doubled-consonant handling).
3. If normalization fails, the assertion is stored in `failed_predicate` — visible in the **Predicate Curation** tab.

### Curation workflow

| Action | Effect |
|---|---|
| **Approve** | The predicate is inserted into `active_predicate` with an auto-assigned group; future chunks can use it |
| **Reject** | The failed predicate record is removed; the assertion is discarded |

### Predicate groups

Every active predicate is classified into one of 7 groups via keyword matching in `PredicateService`:

| Group | Example predicates | Count |
|---|---|---|
| `BUSINESS_RULES` | MANAGES, GOVERNS, OWNS, APPROVES | 44 |
| `DATA_PROCESSING` | PARSES, TRANSFORMS, INDEXES, GENERATES | 20 |
| `EXECUTION_FLOW` | EXECUTES, DELEGATES, TRIGGERS, ORCHESTRATES | 14 |
| `DATA_LIFECYCLE` | WRITES, READS, STORES, DELETES | 14 |
| `COMMUNICATION` | SENDS, PUBLISHES, NOTIFIES, ALERTS | 9 |
| `SECURITY_AUDIT` | VALIDATES, AUTHENTICATES, ENCRYPTS, LOGS | 9 |
| `SYSTEM_INTEGRATION` | DEPENDS, EXTENDS, CONNECTS, INTEGRATES | 8 |

---

## API Reference

### Real-Time MC/Graph Tools

| Method | Path | Parameters | Description |
|---|---|---|---|
| GET | `/api/tools/query_graph` | `text`, `depth` (default 2) | Hybrid BM25 + BFS graph query (Suspended during active Bulk Ingestion) |
| GET | `/api/tools/explain_path_by_assertion_id` | `assertionId` | Trace assertion to source |
| GET | `/api/tools/get_node_neighbour` | `nodeId`, `depth` (default 1) | Get neighbours of a node |
| GET | `/api/tools/get_assertion_by_id` | `assertionId` | Get assertion details |
| GET | `/api/tools/trigger_ingest` | `path` | Force re-ingest a file |
| GET | `/actuator/health` | — | Health check |

### Real-Time Dashboard (Metrics)

| Method | Path | Description |
|---|---|---|
| GET | `/api/dashboard/metrics` | Real-time pipeline metrics (nodes, edges, throughput history, etc.) |
| GET | `/api/dashboard/trends` | Time-series trend data |
| GET | `/api/dashboard/latency` | Pipeline latency percentiles |
| GET | `/api/dashboard/errors` | Recent error events |
| GET | `/api/dashboard/recent` | Most recent pipeline events |

### Bulk Ingestion & Worker API

| Method | Path | Request Body | Description |
|---|---|---|---|
| GET | `/api/bulk/mode` | — | Get the current mode (`realtime` vs `bulk`), active directory, and job status. |
| POST | `/api/bulk/mode` | `{"mode": "bulk"}` | Set the operation mode (`realtime` or `bulk`). Switching to realtime is blocked if a bulk job is active. |
| POST | `/api/bulk/start` | `{"directory": "/path/to/docs"}` | Start a bulk ingestion job scanning and parsing files in the specified directory. |
| POST | `/api/bulk/stop` | — | Stop the active bulk indexing job. |
| GET | `/api/bulk/workers` | — | List all registered active workers, their load, last heartbeat, and hostnames. |
| GET | `/api/bulk/progress` | — | Streams Server-Sent Events (SSE) containing real-time bulk metrics and worker updates. |
| POST | `/api/bulk/clear-dedup` | — | Clears all deduplication checksums and states from Redis. |

### Predicate Curation

| Method | Path | Parameters | Description |
|---|---|---|---|
| GET | `/api/predicates/active` | — | All active predicates with groups |
| GET | `/api/predicates/failed` | — | All failed (unrecognised) predicates |
| POST | `/api/predicates/approve` | `name` | Approve a failed predicate into `active_predicate` |
| POST | `/api/predicates/reject` | `name` | Reject and remove a failed predicate |

### Graph Explorer

| Method | Path | Parameters | Description |
|---|---|---|---|
| GET | `/api/graph/explore` | — | Full graph dump (nodes + edges) for visualisation |
| GET | `/api/graph/paths` | `sourceId`, `targetId` | All paths between two nodes |

---

## Tech Stack

- **Java 21** + **Spring Boot 3.3.5**
- **PostgreSQL 15** (Canonical graph store — assertions, nodes, edges, chunks, documents)
- **OpenSearch 2.13** (Query index — BM25 scoring, adjacency lookup)
- **Apache Kafka 7.6 (KRaft)** (Ingestion event serialization and distributed streaming)
- **Redis 7** (State caching, worker heartbeat registry, deduplication checksum repository, and distributed locks)
- **Groq API / Gemini API** (LLM-based semantic assertion extraction)
- **SQLite 3** (Local state metadata index under `.neosis/state.db`)
- **Vue 3 + Tailwind CSS** (Dashboard SPA interface)
- **Chart.js 4 + D3.js 7** (Metrics rendering and 3D force-directed interactive graphs)

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `Native memory allocation failed` during build | System RAM too low for Gradle daemon | Build using `./gradlew bootJar -x test --no-daemon` |
| Dashboard chart blank | Chart.js canvas sizing | Refresh the page or switch tabs |
| LLM returns unknown predicates | Predicate not in `PredicateType` enum | Approve via Predicate Curation tab, or add to enum |
| File not ingested in Real-Time Mode | Pattern not matched by `.neosis/config.json` | Check `include` patterns; file must match at least one |
| Duplicate assertions or edges in graph | Database duplicate keys | Execute `POST /api/bulk/clear-dedup` to refresh Redis dedup indexes and clear local cache |
| Stale or silent JVM workers in list | Heartbeat network timeout | Registry evicts nodes after 15 seconds of inactivity. Check if target workers are running and verified in `/api/bulk/workers` |
| Ingestion frozen or locked | Redis lock collision | An active lock remains for 30 minutes. If a worker crashed, restart the coordinator, or execute `/api/bulk/clear-dedup` to force release |
