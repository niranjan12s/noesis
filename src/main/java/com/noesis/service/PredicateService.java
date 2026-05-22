package com.noesis.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noesis.entity.ActivePredicateEntity;
import com.noesis.entity.AssertionEntity;
import com.noesis.entity.DocumentEntity;
import com.noesis.entity.EdgeEntity;
import com.noesis.entity.FailedPredicateEntity;
import com.noesis.entity.NodeEntity;
import com.noesis.entity.PendingAssertionEntity;
import com.noesis.entity.PredicateAliasEntity;
import com.noesis.dto.AssertionExtractionResponse;
import com.noesis.repository.ActivePredicateRepository;
import com.noesis.repository.AssertionJpaRepository;
import com.noesis.repository.DocumentRepository;
import com.noesis.repository.EdgeJpaRepository;
import com.noesis.repository.FailedPredicateRepository;
import com.noesis.repository.NodeJpaRepository;
import com.noesis.repository.PendingAssertionRepository;
import com.noesis.repository.PredicateAliasRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PredicateService {

    private final ActivePredicateRepository activePredicateRepository;
    private final FailedPredicateRepository failedPredicateRepository;
    private final PendingAssertionRepository pendingAssertionRepository;
    private final AssertionJpaRepository assertionRepository;
    private final DocumentRepository documentRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private final EdgeJpaRepository edgeRepository;
    private final NodeJpaRepository nodeRepository;
    private final PredicateAliasRepository aliasRepository;

    @Autowired
    @Lazy
    private GraphComponentService graphComponentService;

    private static final String REDIS_KEY        = "noesis:active-predicates";
    private static final String REDIS_ALIAS_KEY  = "noesis:predicate-aliases";
    private static final String EXTRACTION_MODEL = "llama3.2:1b"; // fallback metadata

    // Precomputed index: active predicate names grouped by their string length.
    // Rebuilt whenever the predicate set is refreshed (init / Redis heal).
    // Used by nearestActivePredicate to prune candidates before computing Levenshtein.
    private volatile Map<Integer, List<String>> predicatesByLength = Collections.emptyMap();

    @PostConstruct
    public void init() {
        try {
            log.info("Initializing PredicateService - loading active predicates from DB...");
            List<ActivePredicateEntity> activeInDb = activePredicateRepository.findAll();
            if (activeInDb.isEmpty()) {
                log.warn("No active predicates found in DB — the seed script may not have run yet. " +
                         "Redis cache will be populated by PredicateSeedService after seeding. " +
                         "Extraction during this window will query the DB directly (also empty).");
                return;
            }
            boolean updated = false;
            for (ActivePredicateEntity entity : activeInDb) {
                if (entity.getPredicateGroup() == null || entity.getPredicateGroup().isBlank()) {
                    entity.setPredicateGroup(classifyPredicate(entity.getName()));
                    updated = true;
                }
            }
            if (updated) {
                log.info("Backfilling predicateGroup field for existing active database entries...");
                activePredicateRepository.saveAll(activeInDb);
            }
            cacheActivePredicatesInRedis(activeInDb.stream().map(ActivePredicateEntity::getName).collect(Collectors.toSet()));
        } catch (Exception e) {
            log.error("Failed to initialize active predicates in DB/Redis. Moving forward with database-only fallback.", e);
        }

        // Load alias table into Redis hash — hot path never touches the DB
        try {
            loadAliasesIntoRedis();
        } catch (Exception e) {
            log.error("Failed to load predicate aliases into Redis on startup.", e);
        }
    }

    /**
     * Called by PredicateSeedService after the SQL seed has been applied so that
     * Redis reflects the freshly-seeded active predicates AND any stored aliases.
     */
    public void reloadAllCaches() {
        try {
            List<ActivePredicateEntity> activeInDb = activePredicateRepository.findAll();
            Set<String> names = activeInDb.stream()
                    .map(ActivePredicateEntity::getName)
                    .collect(Collectors.toSet());
            cacheActivePredicatesInRedis(names);
            log.info("Reloaded {} active predicates into Redis after seed.", names.size());
        } catch (Exception e) {
            log.error("Failed to reload active predicate cache after seed.", e);
        }
        try {
            loadAliasesIntoRedis();
        } catch (Exception e) {
            log.error("Failed to reload alias cache after seed.", e);
        }
    }

    private void cacheActivePredicatesInRedis(Set<String> predicates) {
        rebuildLengthIndex(predicates);
        try {
            redisTemplate.opsForValue().set(REDIS_KEY, new ArrayList<>(predicates));
            log.info("Successfully cached {} active predicates in Redis.", predicates.size());
        } catch (Exception e) {
            log.warn("Redis is unavailable. Predicates cached in memory/querying DB directly instead. Error: {}", e.getMessage());
        }
    }

    /** Loads the full alias table from DB into the Redis Hash (replaces any stale entries). */
    private void loadAliasesIntoRedis() {
        List<PredicateAliasEntity> aliases = aliasRepository.findAll();
        if (aliases.isEmpty()) {
            log.info("No predicate aliases found in DB — alias Redis hash will be empty.");
            return;
        }
        Map<Object, Object> aliasMap = new LinkedHashMap<>();
        for (PredicateAliasEntity a : aliases) {
            aliasMap.put(a.getSource(), a.getTarget());
        }
        try {
            redisTemplate.delete(REDIS_ALIAS_KEY);
            redisTemplate.opsForHash().putAll(REDIS_ALIAS_KEY, aliasMap);
            log.info("Cached {} predicate aliases in Redis hash '{}'.", aliases.size(), REDIS_ALIAS_KEY);
        } catch (Exception e) {
            log.warn("Failed to populate alias Redis hash: {}", e.getMessage());
        }
    }

    /**
     * Redis-only alias lookup. Called on the ingestion hot-path — never queries the DB.
     * Returns the canonical active predicate the given source maps to, or null if no alias exists.
     */
    public String resolveAlias(String upperName) {
        try {
            Object target = redisTemplate.opsForHash().get(REDIS_ALIAS_KEY, upperName);
            return target != null ? target.toString() : null;
        } catch (Exception e) {
            log.warn("Redis alias lookup failed for '{}', skipping alias step: {}", upperName, e.getMessage());
            return null;
        }
    }

    /**
     * Asynchronously rebuilds the alias Redis hash from the DB.
     * Called after every user-initiated map action — ingestion never waits for it.
     */
    @Async
    public void refreshAliasCache() {
        try {
            loadAliasesIntoRedis();
            log.info("Async alias cache refresh complete.");
        } catch (Exception e) {
            log.error("Async alias cache refresh failed.", e);
        }
    }

    private void rebuildLengthIndex(Set<String> predicates) {
        Map<Integer, List<String>> map = new HashMap<>();
        for (String p : predicates) {
            map.computeIfAbsent(p.length(), k -> new ArrayList<>()).add(p);
        }
        this.predicatesByLength = map;
        log.debug("Rebuilt length index for {} predicates ({} distinct lengths)", predicates.size(), map.size());
    }

    @SuppressWarnings("unchecked")
    public Set<String> getActivePredicates() {
        try {
            List<String> list = (List<String>) redisTemplate.opsForValue().get(REDIS_KEY);
            if (list != null && !list.isEmpty()) {
                return new HashSet<>(list);
            }
        } catch (Exception e) {
            log.warn("Failed to retrieve active predicates from Redis, falling back to PostgreSQL.", e);
        }

        // Fallback to PostgreSQL
        try {
            Set<String> dbPredicates = activePredicateRepository.findAll().stream()
                    .map(ActivePredicateEntity::getName)
                    .collect(Collectors.toSet());
            if (!dbPredicates.isEmpty()) {
                // Try to heal cache
                try {
                    redisTemplate.opsForValue().set(REDIS_KEY, new ArrayList<>(dbPredicates));
                } catch (Exception ignored) {}
                return dbPredicates;
            }
        } catch (Exception e) {
            log.error("PostgreSQL active predicates fetch failed.", e);
        }

        return Collections.emptySet();
    }

    @Transactional
    public void recordFailedPredicate(String name, String docPath, String sampleText, AssertionExtractionResponse dto, UUID chunkId, UUID documentId, int version, UUID runId) {
        if (name == null || name.isBlank()) return;
        String upperName = name.toUpperCase().trim();

        // Check if this predicate is already active or a near-miss of an active one
        String active = nearestActivePredicate(upperName, 2);
        if (active != null) {
            if (!active.equals(upperName)) {
                log.info("Predicate '{}' collapsed to active '{}' (Levenshtein distance ≤ 2), saving assertion directly", upperName, active);
            }
            saveCollapsedAssertion(active, dto, chunkId, documentId, version, runId);
            return;
        }

        log.info("Recording failed predicate: '{}' from document: {}", upperName, docPath);

        // 1. Save or Update FailedPredicateEntity
        FailedPredicateEntity failed = failedPredicateRepository.findById(upperName).orElse(null);
        if (failed == null) {
            failed = FailedPredicateEntity.builder()
                    .name(upperName)
                    .occurrenceCount(1)
                    .lastSeenDocument(docPath)
                    .sampleRawText(sampleText)
                    .updatedAt(Instant.now())
                    .predicateGroup(classifyPredicate(upperName))
                    .build();
        } else {
            failed.setOccurrenceCount(failed.getOccurrenceCount() + 1);
            failed.setLastSeenDocument(docPath);
            failed.setSampleRawText(sampleText);
            failed.setUpdatedAt(Instant.now());
            // Update group in case it was somehow empty
            if (failed.getPredicateGroup() == null || failed.getPredicateGroup().isBlank()) {
                failed.setPredicateGroup(classifyPredicate(upperName));
            }
        }
        failedPredicateRepository.save(failed);

        // 2. Serialize attributes and save PendingAssertionEntity
        String attributesJson = null;
        if (dto.getAttributes() != null && !dto.getAttributes().isEmpty()) {
            try {
                attributesJson = objectMapper.writeValueAsString(dto.getAttributes());
            } catch (Exception e) {
                log.warn("Failed to serialize assertion attributes to JSON.", e);
            }
        }

        PendingAssertionEntity pending = PendingAssertionEntity.builder()
                .id(UUID.randomUUID())
                .chunkId(chunkId)
                .documentId(documentId)
                .documentVersion(version)
                .ingestionRunId(runId)
                .subject(dto.getSubjectText())
                .predicate(upperName)
                .object(dto.getObjectText())
                .rawText(dto.getRawText())
                .normalizedText(dto.getNormalizedText())
                .attributes(attributesJson)
                .createdAt(Instant.now())
                .build();
        pendingAssertionRepository.save(pending);
    }

    private void saveCollapsedAssertion(String predicate, AssertionExtractionResponse dto, UUID chunkId, UUID documentId, int version, UUID runId) {
        try {
            String canonicalAssertion = String.format("%s|%s|%s",
                    dto.getSubjectText().toUpperCase(),
                    predicate,
                    dto.getObjectText().toUpperCase()
            );

            Map<String, Object> attributes = dto.getAttributes();

            AssertionEntity entity = AssertionEntity.builder()
                    .id(UUID.randomUUID())
                    .chunkId(chunkId)
                    .documentId(documentId)
                    .documentVersion(version)
                    .ingestionRunId(runId)
                    .rawText(dto.getRawText())
                    .normalizedText(dto.getNormalizedText())
                    .subject(dto.getSubjectText())
                    .predicate(predicate)
                    .object(dto.getObjectText())
                    .attributes(attributes)
                    .extractionModel(EXTRACTION_MODEL)
                    .semanticChecksum(computeSha256(canonicalAssertion))
                    .evidenceChecksum(computeSha256(dto.getRawText()))
                    .build();

            assertionRepository.save(entity);

            DocumentEntity doc = documentRepository.findById(documentId.toString()).orElse(null);
            if (doc != null) {
                doc.setTotalAssertions(doc.getTotalAssertions() + 1);
                documentRepository.save(doc);
            }

            log.debug("Saved collapsed assertion for predicate '{}' (chunk {})", predicate, chunkId);
        } catch (Exception e) {
            log.error("Failed to save collapsed assertion for predicate '{}': {}", predicate, e.getMessage(), e);
        }
    }

    /**
     * Finds the active predicate whose Levenshtein distance from {@code name}
     * is ≤ {@code maxDistance}.  Returns the matching active predicate name, or
     * {@code null} if no active predicate is within the threshold.
     */
    private String nearestActivePredicate(String name, int maxDistance) {
        String upper = name.toUpperCase().trim();
        int len = upper.length();
        String best = null;
        int bestDist = maxDistance + 1;
        Map<Integer, List<String>> index = this.predicatesByLength;
        // Only check lengths that could possibly be within maxDistance edits
        for (int l = Math.max(1, len - maxDistance); l <= len + maxDistance; l++) {
            List<String> candidates = index.get(l);
            if (candidates == null) continue;
            for (String active : candidates) {
                int dist = levenshteinDistance(upper, active);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = active;
                }
            }
        }
        return bestDist <= maxDistance ? best : null;
    }

    /**
     * Classic Wagner–Fischer Levenshtein distance calculation.
     * Measures the minimum number of single-character edits (insertions,
     * deletions, substitutions) required to transform {@code a} into {@code b}.
     *
     * <p>The algorithm uses a striped (two-row) DP table to keep memory O(n).
     */
    static int levenshteinDistance(String a, String b) {
        int m = a.length();
        int n = b.length();
        // Use the shorter string as the columns to minimise allocation
        if (m > n) {
            String tmp = a; a = b; b = tmp;
            int t = m; m = n; n = t;
        }
        int[] prev = new int[m + 1];
        int[] curr = new int[m + 1];
        for (int j = 0; j <= m; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= n; i++) {
            curr[0] = i;
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(j - 1) == b.charAt(i - 1) ? 0 : 1;
                curr[j] = Math.min(
                        Math.min(curr[j - 1] + 1, prev[j] + 1),
                        prev[j - 1] + cost
                );
            }
            int[] swap = prev; prev = curr; curr = swap;
        }
        return prev[m];
    }

    @Transactional
    public void approvePredicate(String name) {
        if (name == null || name.isBlank()) return;
        String upperName = name.toUpperCase();

        log.info("Approving predicate: '{}'", upperName);

        // 1. Move to active list
        if (!activePredicateRepository.existsById(upperName)) {
            activePredicateRepository.save(ActivePredicateEntity.builder()
                    .name(upperName)
                    .createdAt(Instant.now())
                    .predicateGroup(classifyPredicate(upperName))
                    .build());
        }

        // 2. Remove from failed predicates list
        failedPredicateRepository.deleteById(upperName);

        // 3. Invalidate/Refresh Redis cache
        try {
            Set<String> activePredicates = activePredicateRepository.findAll().stream()
                    .map(ActivePredicateEntity::getName)
                    .collect(Collectors.toSet());
            cacheActivePredicatesInRedis(activePredicates);
        } catch (Exception e) {
            log.warn("Could not reload Redis cache: {}", e.getMessage());
        }

        // 4. Retrieve and reprocess all cached pending assertions
        List<PendingAssertionEntity> pendingList = pendingAssertionRepository.findByPredicate(upperName);
        if (pendingList.isEmpty()) {
            log.info("No pending assertions found for approved predicate: '{}'", upperName);
            return;
        }

        log.info("Reprocessing {} pending assertions for approved predicate: '{}'", pendingList.size(), upperName);
        List<AssertionEntity> realAssertions = new ArrayList<>();
        Set<UUID> affectedRunIds = new HashSet<>();
        Map<String, Integer> documentToAssertionCount = new HashMap<>();

        for (PendingAssertionEntity pending : pendingList) {
            try {
                String canonicalAssertion = String.format("%s|%s|%s",
                        pending.getSubject().toUpperCase(),
                        pending.getPredicate().toUpperCase(),
                        pending.getObject().toUpperCase()
                );

                Map<String, Object> attributes = null;
                if (pending.getAttributes() != null && !pending.getAttributes().isBlank()) {
                    try {
                        attributes = objectMapper.readValue(pending.getAttributes(), new TypeReference<Map<String, Object>>() {});
                    } catch (Exception e) {
                        log.warn("Failed to deserialize attributes from pending assertion.", e);
                    }
                }

                AssertionEntity real = AssertionEntity.builder()
                        .id(UUID.randomUUID())
                        .chunkId(pending.getChunkId())
                        .documentId(pending.getDocumentId())
                        .documentVersion(pending.getDocumentVersion())
                        .ingestionRunId(pending.getIngestionRunId())
                        .rawText(pending.getRawText())
                        .normalizedText(pending.getNormalizedText())
                        .subject(pending.getSubject())
                        .predicate(pending.getPredicate().toUpperCase())
                        .object(pending.getObject())
                        .attributes(attributes)
                        .extractionModel(EXTRACTION_MODEL)
                        .semanticChecksum(computeSha256(canonicalAssertion))
                        .evidenceChecksum(computeSha256(pending.getRawText()))
                        .build();

                realAssertions.add(real);
                affectedRunIds.add(pending.getIngestionRunId());

                String docIdStr = pending.getDocumentId().toString();
                documentToAssertionCount.put(docIdStr, documentToAssertionCount.getOrDefault(docIdStr, 0) + 1);

            } catch (Exception e) {
                log.error("Failed to reprocess pending assertion ID {}", pending.getId(), e);
            }
        }

        if (!realAssertions.isEmpty()) {
            assertionRepository.saveAll(realAssertions);
            log.info("Successfully persisted {} real assertions.", realAssertions.size());

            // Update assertion counts on documents
            for (Map.Entry<String, Integer> entry : documentToAssertionCount.entrySet()) {
                DocumentEntity doc = documentRepository.findById(entry.getKey()).orElse(null);
                if (doc != null) {
                    doc.setTotalAssertions(doc.getTotalAssertions() + entry.getValue());
                    documentRepository.save(doc);
                }
            }
        }

        // 5. Delete all processed pending assertions (before async graph rebuild)
        pendingAssertionRepository.deleteByPredicate(upperName);

        // 6. Trigger graph recompilation asynchronously (may call LLM, rate-limited)
        Set<UUID> runsToRebuild = new HashSet<>(affectedRunIds);
        if (!runsToRebuild.isEmpty()) {
            CompletableFuture.runAsync(() -> {
                for (UUID runId : runsToRebuild) {
                    try {
                        log.info("Async graph rebuild for run: {}", runId);
                        graphComponentService.buildGraphComponents(runId.toString());
                    } catch (Exception e) {
                        log.error("Failed to rebuild graph components for ingestion run {}", runId, e);
                    }
                }
            });
        }
    }

    @Transactional
    public void rejectPredicate(String name) {
        if (name == null || name.isBlank()) return;
        String upperName = name.toUpperCase();

        log.info("Rejecting predicate: '{}' (deleting pending assertions)", upperName);

        failedPredicateRepository.deleteById(upperName);
        pendingAssertionRepository.deleteByPredicate(upperName);
    }

    /**
     * Maps a failed/unrecognized predicate to an existing (or new) active predicate,
     * reprocesses all pending assertions under the target predicate, persists the alias
     * to the DB, and asynchronously refreshes the Redis alias hash so the ingestion
     * hot-path resolves future occurrences silently without hitting the failed list.
     */
    @Transactional
    public void mapAndReprocessPredicate(String failedName, String targetName) {
        if (failedName == null || failedName.isBlank()) return;
        if (targetName == null || targetName.isBlank()) return;

        String upperFailed = failedName.toUpperCase().trim();
        String upperTarget = targetName.toUpperCase().trim();

        log.info("Mapping predicate '{}' → '{}'", upperFailed, upperTarget);

        // 1. Ensure target is an active predicate (supports brand-new canonical names too)
        if (!activePredicateRepository.existsById(upperTarget)) {
            activePredicateRepository.save(ActivePredicateEntity.builder()
                    .name(upperTarget)
                    .createdAt(Instant.now())
                    .predicateGroup(classifyPredicate(upperTarget))
                    .build());
            log.info("Registered new active predicate '{}' during map operation.", upperTarget);
            // Refresh active predicate cache
            Set<String> activePredicates = activePredicateRepository.findAll().stream()
                    .map(ActivePredicateEntity::getName)
                    .collect(Collectors.toSet());
            cacheActivePredicatesInRedis(activePredicates);
        }

        // 2. Upsert alias to DB (safe to re-map to a different target later)
        PredicateAliasEntity alias = PredicateAliasEntity.builder()
                .source(upperFailed)
                .target(upperTarget)
                .createdAt(Instant.now())
                .createdBy("USER")
                .build();
        aliasRepository.save(alias);
        log.info("Persisted alias '{}' → '{}' to DB.", upperFailed, upperTarget);

        // 3. Async Redis refresh — ingestion hot-path updated without blocking this transaction
        refreshAliasCache();

        // 4. Reprocess all pending assertions under the new predicate name
        List<PendingAssertionEntity> pendingList = pendingAssertionRepository.findByPredicate(upperFailed);
        if (pendingList.isEmpty()) {
            log.info("No pending assertions found for '{}', alias stored for future use.", upperFailed);
        } else {
            log.info("Reprocessing {} pending assertions for '{}' → '{}'", pendingList.size(), upperFailed, upperTarget);
        }

        List<AssertionEntity> realAssertions = new ArrayList<>();
        Set<UUID> affectedRunIds = new HashSet<>();
        Map<String, Integer> documentToAssertionCount = new HashMap<>();

        for (PendingAssertionEntity pending : pendingList) {
            try {
                String canonicalAssertion = String.format("%s|%s|%s",
                        pending.getSubject().toUpperCase(),
                        upperTarget,
                        pending.getObject().toUpperCase());

                Map<String, Object> attributes = null;
                if (pending.getAttributes() != null && !pending.getAttributes().isBlank()) {
                    try {
                        attributes = objectMapper.readValue(pending.getAttributes(), new TypeReference<Map<String, Object>>() {});
                    } catch (Exception e) {
                        log.warn("Failed to deserialize attributes for pending assertion {}.", pending.getId());
                    }
                }

                AssertionEntity real = AssertionEntity.builder()
                        .id(UUID.randomUUID())
                        .chunkId(pending.getChunkId())
                        .documentId(pending.getDocumentId())
                        .documentVersion(pending.getDocumentVersion())
                        .ingestionRunId(pending.getIngestionRunId())
                        .rawText(pending.getRawText())
                        .normalizedText(pending.getNormalizedText())
                        .subject(pending.getSubject())
                        .predicate(upperTarget)   // rewritten to the mapped target
                        .object(pending.getObject())
                        .attributes(attributes)
                        .extractionModel(EXTRACTION_MODEL)
                        .semanticChecksum(computeSha256(canonicalAssertion))
                        .evidenceChecksum(computeSha256(pending.getRawText()))
                        .build();

                realAssertions.add(real);
                affectedRunIds.add(pending.getIngestionRunId());
                String docIdStr = pending.getDocumentId().toString();
                documentToAssertionCount.merge(docIdStr, 1, Integer::sum);
            } catch (Exception e) {
                log.error("Failed to reprocess pending assertion ID {}.", pending.getId(), e);
            }
        }

        if (!realAssertions.isEmpty()) {
            assertionRepository.saveAll(realAssertions);
            log.info("Persisted {} assertions under predicate '{}'.", realAssertions.size(), upperTarget);
            for (Map.Entry<String, Integer> entry : documentToAssertionCount.entrySet()) {
                DocumentEntity doc = documentRepository.findById(entry.getKey()).orElse(null);
                if (doc != null) {
                    doc.setTotalAssertions(doc.getTotalAssertions() + entry.getValue());
                    documentRepository.save(doc);
                }
            }
        }

        // 5. Clean up failed/pending registries
        failedPredicateRepository.deleteById(upperFailed);
        pendingAssertionRepository.deleteByPredicate(upperFailed);

        // 6. Trigger async graph rebuild for affected runs
        if (!affectedRunIds.isEmpty()) {
            Set<UUID> runsToRebuild = new HashSet<>(affectedRunIds);
            CompletableFuture.runAsync(() -> {
                for (UUID runId : runsToRebuild) {
                    try {
                        log.info("Async graph rebuild triggered for run: {}", runId);
                        graphComponentService.buildGraphComponents(runId.toString());
                    } catch (Exception e) {
                        log.error("Graph rebuild failed for run {}.", runId, e);
                    }
                }
            });
        }
    }

    /** Returns all stored aliases (for the /aliases inspection endpoint). */
    public List<PredicateAliasEntity> getAllAliases() {
        return aliasRepository.findAll();
    }

    @Transactional
    public void revokePredicate(String name) {
        if (name == null || name.isBlank()) return;
        String upperName = name.toUpperCase();

        log.info("Revoking predicate: '{}' — cleaning up edges, nodes, and assertions", upperName);

        // 1. Remove from active list
        activePredicateRepository.deleteById(upperName);

        // 2. Delete all edges with this predicate, track connected nodes
        List<EdgeEntity> edges = edgeRepository.findByPredicate(upperName);
        Set<UUID> connectedNodeIds = new HashSet<>();
        for (EdgeEntity edge : edges) {
            connectedNodeIds.add(edge.getFromNodeId());
            connectedNodeIds.add(edge.getToNodeId());
        }
        edgeRepository.deleteAll(edges);
        log.info("Deleted {} edges for predicate '{}'", edges.size(), upperName);

        // 3. Delete all assertions with this predicate
        List<AssertionEntity> assertions = assertionRepository.findByPredicate(upperName);
        assertionRepository.deleteAll(assertions);
        log.info("Deleted {} assertions for predicate '{}'", assertions.size(), upperName);

        // 4. Delete orphaned nodes (no remaining edges referencing them)
        int orphanedCount = 0;
        for (UUID nodeId : connectedNodeIds) {
            List<EdgeEntity> edgesFrom = edgeRepository.findByFromNodeId(nodeId);
            List<EdgeEntity> edgesTo = edgeRepository.findByToNodeId(nodeId);
            if (edgesFrom.isEmpty() && edgesTo.isEmpty()) {
                nodeRepository.deleteById(nodeId);
                orphanedCount++;
            }
        }
        log.info("Deleted {} orphaned nodes for predicate '{}'", orphanedCount, upperName);

        // 5. Refresh Redis cache
        try {
            Set<String> activePredicates = activePredicateRepository.findAll().stream()
                    .map(ActivePredicateEntity::getName)
                    .collect(Collectors.toSet());
            cacheActivePredicatesInRedis(activePredicates);
        } catch (Exception e) {
            log.warn("Could not reload Redis cache after revoke: {}", e.getMessage());
        }
    }

    @Transactional
    public int autoApprovePredicates(int minOccurrences) {
        log.info("Auto-approving predicates with occurrence count >= {}", minOccurrences);
        List<FailedPredicateEntity> candidates = failedPredicateRepository.findByOccurrenceCountGreaterThanEqual(minOccurrences);
        if (candidates.isEmpty()) {
            log.info("No failed predicates meet the minimum occurrence threshold of {}", minOccurrences);
            return 0;
        }
        int count = 0;
        for (FailedPredicateEntity candidate : candidates) {
            try {
                approvePredicate(candidate.getName());
                count++;
            } catch (Exception e) {
                log.error("Failed to auto-approve predicate '{}'", candidate.getName(), e);
            }
        }
        log.info("Auto-approved {} out of {} eligible predicates", count, candidates.size());
        return count;
    }

    private String computeSha256(String input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder hexString = new StringBuilder(2 * hash.length);
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    public static String classifyPredicate(String predicateName) {
        if (predicateName == null || predicateName.isBlank()) {
            return "BUSINESS_RULES";
        }
        String upper = predicateName.toUpperCase();

        // 1. DATA_LIFECYCLE keywords
        if (upper.contains("WRITE") || upper.contains("SAVE") || upper.contains("STORE") ||
            upper.contains("PERSIST") || upper.contains("DELETE") || upper.contains("REMOVE") ||
            upper.contains("CREATE") || upper.contains("ADD") || upper.contains("UPDATE") ||
            upper.contains("READ") || upper.contains("LOAD") || upper.contains("GET") ||
            upper.contains("FETCH") || upper.contains("RETRIEVE") || upper.contains("RECORD") ||
            upper.contains("KEEP") || upper.contains("HOLD") || upper.contains("CONTAIN") ||
            upper.contains("INCLUDE") || upper.contains("INGEST")) {
            return "DATA_LIFECYCLE";
        }

        // 2. COMMUNICATION keywords
        if (upper.contains("SEND") || upper.contains("RECEIVE") || upper.contains("PUBLISH") ||
            upper.contains("CONSUME") || upper.contains("DELIVER") || upper.contains("TRANSMIT") ||
            upper.contains("NOTIFY") || upper.contains("ALERT") || upper.contains("MAIL") ||
            upper.contains("POST") || upper.contains("CONTACT") || upper.contains("SIGNAL") ||
            upper.contains("SUBSCRIBE")) {
            return "COMMUNICATION";
        }

        // 3. EXECUTION_FLOW keywords
        if (upper.contains("RUN") || upper.contains("EXECUTE") || upper.contains("START") ||
            upper.contains("STOP") || upper.contains("LAUNCH") || upper.contains("PROCESS") ||
            upper.contains("TRIGGER") || upper.contains("PERFORM") || upper.contains("DELEGATE") ||
            upper.contains("ORCHESTRATE") || upper.contains("COORDINATE") || upper.contains("CALL") ||
            upper.contains("INVOKE") || upper.contains("ROUTE") || upper.contains("FORWARD")) {
            return "EXECUTION_FLOW";
        }

        // 4. SECURITY_AUDIT keywords
        if (upper.contains("SECURE") || upper.contains("ENCRYPT") || upper.contains("DECRYPT") ||
            upper.contains("VALIDATE") || upper.contains("CHECK") || upper.contains("VERIFY") ||
            upper.contains("AUTHENTICATE") || upper.contains("AUTHORIZE") || upper.contains("LOG") ||
            upper.contains("MONITOR") || upper.contains("TRACK") || upper.contains("AUDIT")) {
            return "SECURITY_AUDIT";
        }

        // 5. DATA_PROCESSING keywords
        if (upper.contains("PARSE") || upper.contains("TRANSFORM") || upper.contains("CONVERT") ||
            upper.contains("EXTRACT") || upper.contains("INDEX") || upper.contains("NORMALISE") ||
            upper.contains("NORMALIZE") || upper.contains("MAP") || upper.contains("FORMAT") ||
            upper.contains("GENERATE") || upper.contains("PRODUCE") || upper.contains("FILTER") ||
            upper.contains("AGGREGATE") || upper.contains("SPLIT") || upper.contains("BUILD") ||
            upper.contains("DEDUPLICATE") || upper.contains("CORRELATE") || upper.contains("ANALYZE") ||
            upper.contains("DETECT") || upper.contains("EVALUATE") || upper.contains("PROPAGATE") ||
            upper.contains("VISUALIZE") || upper.contains("RENDER") || upper.contains("SCRAPE")) {
            return "DATA_PROCESSING";
        }

        // 6. SYSTEM_INTEGRATION keywords
        if (upper.contains("INHERIT") || upper.contains("DEPEND") || upper.contains("EXTEND") ||
            upper.contains("IMPLEMENT") || upper.contains("IMPORT") || upper.contains("EXPORT") ||
            upper.contains("LINK") || upper.contains("ATTACH") || upper.contains("MOUNT") ||
            upper.contains("BIND") || upper.contains("CONNECT") || upper.contains("INTEGRATE") ||
            upper.contains("SUPPORT") || upper.contains("USE") || upper.contains("PART_OF") ||
            upper.contains("ASSIGNED_TO") || upper.contains("BASED_ON")) {
            return "SYSTEM_INTEGRATION";
        }

        // 7. Default BUSINESS_RULES
        return "BUSINESS_RULES";
    }
}
