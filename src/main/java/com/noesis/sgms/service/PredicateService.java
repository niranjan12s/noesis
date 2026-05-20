package com.noesis.sgms.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noesis.sgms.dto.AssertionExtractionResponse;
import com.noesis.sgms.entity.ActivePredicateEntity;
import com.noesis.sgms.entity.AssertionEntity;
import com.noesis.sgms.entity.FailedPredicateEntity;
import com.noesis.sgms.entity.PendingAssertionEntity;
import com.noesis.sgms.entity.PredicateType;
import com.noesis.sgms.entity.DocumentEntity;
import com.noesis.sgms.repository.ActivePredicateRepository;
import com.noesis.sgms.repository.AssertionJpaRepository;
import com.noesis.sgms.repository.DocumentRepository;
import com.noesis.sgms.repository.FailedPredicateRepository;
import com.noesis.sgms.repository.PendingAssertionRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
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

    @Autowired
    @Lazy
    private GraphComponentService graphComponentService;

    private static final String REDIS_KEY = "neosis:active-predicates";
    private static final String EXTRACTION_MODEL = "llama3.2:1b"; // fallback metadata

    @PostConstruct
    public void init() {
        try {
            log.info("Initializing PredicateService - dynamic predicate checks...");
            List<ActivePredicateEntity> activeInDb = activePredicateRepository.findAll();
            if (activeInDb.isEmpty()) {
                log.info("Pre-populating active_predicates table from PredicateType enum...");
                List<ActivePredicateEntity> toSave = Arrays.stream(PredicateType.values())
                        .map(p -> ActivePredicateEntity.builder()
                                .name(p.name())
                                .createdAt(Instant.now())
                                .predicateGroup(classifyPredicate(p.name()))
                                .build())
                        .collect(Collectors.toList());
                activePredicateRepository.saveAll(toSave);
                activeInDb = toSave;
            } else {
                // Backfill predicate group for existing records if they are null
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
            }
            cacheActivePredicatesInRedis(activeInDb.stream().map(ActivePredicateEntity::getName).collect(Collectors.toSet()));
        } catch (Exception e) {
            log.error("Failed to initialize active predicates in DB/Redis. Moving forward with database-only fallback.", e);
        }
    }

    private void cacheActivePredicatesInRedis(Set<String> predicates) {
        try {
            redisTemplate.opsForValue().set(REDIS_KEY, new ArrayList<>(predicates));
            log.info("Successfully cached {} active predicates in Redis.", predicates.size());
        } catch (Exception e) {
            log.warn("Redis is unavailable. Predicates cached in memory/querying DB directly instead. Error: {}", e.getMessage());
        }
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
            log.error("PostgreSQL active predicates fetch failed. Falling back to PredicateType enum.", e);
        }

        // Final fallback to compiled enum values
        return Arrays.stream(PredicateType.values())
                .map(Enum::name)
                .collect(Collectors.toSet());
    }

    @Transactional
    public void recordFailedPredicate(String name, String docPath, String sampleText, AssertionExtractionResponse dto, UUID chunkId, UUID documentId, int version, UUID runId) {
        if (name == null || name.isBlank()) return;
        String upperName = name.toUpperCase();

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

            // 5. Trigger graph recompilation for affected ingestion runs
            for (UUID runId : affectedRunIds) {
                try {
                    log.info("Triggering graph rebuild for run: {}", runId);
                    graphComponentService.buildGraphComponents(runId.toString());
                } catch (Exception e) {
                    log.error("Failed to rebuild graph components for ingestion run {}", runId, e);
                }
            }
        }

        // 6. Delete all processed pending assertions
        pendingAssertionRepository.deleteByPredicate(upperName);
    }

    @Transactional
    public void rejectPredicate(String name) {
        if (name == null || name.isBlank()) return;
        String upperName = name.toUpperCase();

        log.info("Rejecting predicate: '{}' (deleting pending assertions)", upperName);

        failedPredicateRepository.deleteById(upperName);
        pendingAssertionRepository.deleteByPredicate(upperName);
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
