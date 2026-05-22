package com.noesis.service;

import com.noesis.client.LlmClient;
import com.noesis.dto.AssertionExtractionResponse;
import com.noesis.entity.*;
import com.noesis.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
public class DocumentPipelineIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("noesis")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    private AssertionExtractionService assertionExtractionService;

    @Autowired
    private PredicateService predicateService;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private ChunkJpaRepository chunkJpaRepository;

    @Autowired
    private AssertionJpaRepository assertionJpaRepository;

    @Autowired
    private PendingAssertionRepository pendingAssertionRepository;

    @Autowired
    private FailedPredicateRepository failedPredicateRepository;

    @Autowired
    private ActivePredicateRepository activePredicateRepository;

    @MockBean
    private LlmClient llmClient;

    private String documentId;
    private UUID chunkId;
    private UUID runId;

    private static final Set<String> SEED_PREDICATES = Set.of(
        "ACCEPTS", "ADDS", "AGGREGATES", "ALERTS", "ALLOWS",
        "ANALYZES", "ANSWERS", "APPLIES", "ARCHIVES", "ARCHIVES_TO",
        "ASSIGNED_TO", "ATTACHES", "AUTHENTICATES", "AUTHORIZES", "BASED_ON",
        "BECOMES", "BUILDS", "CACHES", "CALCULATES", "CALLS",
        "CARRIES", "CHECKS", "COLLECTS", "CONFIGURED_BY", "CONFIGURES",
        "CONSISTS_OF", "CONSUMED_ALONGSIDE", "CONSUMES", "CONTACTS", "CONTAINS",
        "COORDINATES", "CORRELATES", "COVERS", "CREATES", "DECRYPTS",
        "DEDUPLICATES", "DEFINES", "DELEGATES", "DELETES", "DELIVERS",
        "DEPENDS_ON", "DETECTS", "DISTINGUISHES", "DRIVES", "ENABLES",
        "ENCRYPTS", "ENRICHES", "ENSURES", "ESTABLISHES", "EVALUATES",
        "EXECUTES", "EXPOSES", "EXTENDS", "EXTRACTS", "FAILS_OVER",
        "FEEDS", "FETCHES", "FILTERS", "FLOW", "FOLLOWS",
        "FORWARDS", "GENERATES", "GUARANTEES", "HANDLES", "HOLDS",
        "IDENTIFIES", "IMPLEMENTS", "INCLUDES", "INDEXES", "INGESTS",
        "INITIALIZES", "INSTRUMENTS", "INTEGRATES", "INVOKES", "ISSUES",
        "JOINS", "LOGS", "MAINTAINED_BY", "MAINTAINS", "MANAGES",
        "MANDATES", "MAPS", "MATCHES", "MONITORS", "NEEDS",
        "NEGOTIATES", "NORMALIZES", "NOTIFIES", "OPERATES", "ORCHESTRATES",
        "PARSES", "PART_OF", "PARTITIONS", "PASSES", "PERFORMS",
        "PERSISTS", "PROCESSES", "PRODUCES", "PROPAGATES", "PROVIDES",
        "PROVISIONS", "PUBLISHES", "QUERIES", "READS", "REASSIGNS",
        "RECALCULATES", "RECEIVES", "RECONSTRUCTS", "RECORDS", "REGISTERS",
        "RENDERS", "REPLAYS", "REPRESENTED_BY", "REQUIRES", "RESIDES",
        "RESIDES_IN", "RESPONDS", "RESPONSIBLE_FOR", "RETRIEVES", "RETURNS",
        "ROUTES", "RUNS", "SCALES", "SCHEDULES", "SCRAPES",
        "SENDS", "SEPARATES", "SERVES", "SHARED_ACROSS", "SHARED_BY",
        "SHARED_WITH", "SHARES", "SPECIFIES", "SPLITS", "STARTS",
        "STOPS", "STORES", "SUBMITS", "SUBSCRIBES", "SUPPORTS",
        "SYNCHRONIZES", "TRACKS", "TRANSFORMS", "TRANSITIONS", "TRANSITIONS_TO",
        "TRANSMITS", "TRIGGERS", "UNDER", "UPDATES", "USES",
        "VALIDATES", "VISUALIZES", "WRITES"
    );

    @BeforeEach
    public void setUp() {
        // Clean database tables to ensure fresh test runs
        assertionJpaRepository.deleteAll();
        pendingAssertionRepository.deleteAll();
        failedPredicateRepository.deleteAll();
        chunkJpaRepository.deleteAll();
        documentRepository.deleteAll();
        activePredicateRepository.deleteAll();

        // Seed initial active predicates
        Instant now = Instant.now();
        List<ActivePredicateEntity> seed = SEED_PREDICATES.stream()
                .map(name -> ActivePredicateEntity.builder()
                        .name(name)
                        .createdAt(now)
                        .predicateGroup(PredicateService.classifyPredicate(name))
                        .build())
                .collect(Collectors.toList());
        activePredicateRepository.saveAll(seed);
        predicateService.init(); // backfill groups + cache in Redis

        // Setup dynamic entities
        documentId = UUID.randomUUID().toString();
        runId = UUID.randomUUID();
        chunkId = UUID.randomUUID();

        DocumentEntity document = DocumentEntity.builder()
                .id(documentId)
                .name("test-doc.md")
                .absolutePath("C:/test-doc.md")
                .checksum("test-sha-12345")
                .version(1)
                .ingestionRunId(runId.toString())
                .status(DocumentStatus.PROCESSING_ASSERTIONS)
                .totalAssertions(0)
                .createdAt(now)
                .updatedAt(now)
                .build();
        documentRepository.save(document);

        ChunkEntity chunk = ChunkEntity.builder()
                .id(chunkId)
                .documentId(UUID.fromString(documentId))
                .documentVersion(1)
                .sectionPath("Section 1")
                .content("Noesis system creates memory and inherits_from database. System implementes pipeline.")
                .normalizedContent("Noesis system creates memory and inherits_from database. System implementes pipeline.")
                .chunkChecksum("dummy-checksum-12345")
                .sequenceNumber(0)
                .createdAt(now)
                .build();
        chunkJpaRepository.save(chunk);

        // Re-mock after init() reloads the bean
        when(llmClient.extractAssertions(anyString(), anyString())).thenReturn(Collections.emptyList());
    }

    @Test
    @Transactional
    public void testAssertionPipelineWithUnrecognizedPredicateAndReprocessing() throws Exception {
        // 1. Mock LLM Response: One active predicate, one unrecognized predicate
        AssertionExtractionResponse assertion1 = new AssertionExtractionResponse();
        assertion1.setSubjectText("Noesis");
        assertion1.setPredicate("CREATES");
        assertion1.setObjectText("memory");
        assertion1.setRawText("creates memory");
        assertion1.setNormalizedText("Noesis creates memory");
        assertion1.setAttributes(new HashMap<>());

        AssertionExtractionResponse assertion2 = new AssertionExtractionResponse();
        assertion2.setSubjectText("Noesis");
        assertion2.setPredicate("INHERITS_FROM"); // Unrecognized predicate!
        assertion2.setObjectText("database");
        assertion2.setRawText("inherits_from database");
        assertion2.setNormalizedText("Noesis inherits_from database");
        assertion2.setAttributes(new HashMap<>());

        List<AssertionExtractionResponse> mockedList = Arrays.asList(assertion1, assertion2);
        when(llmClient.extractAssertions(anyString(), anyString())).thenReturn(mockedList);

        // 2. Trigger Ingestion extraction process
        assertionExtractionService.processDocumentAssertions(documentId);

        // 3. Verify valid assertion persisted
        List<AssertionEntity> savedAssertions = assertionJpaRepository.findAll();
        assertEquals(1, savedAssertions.size());
        assertEquals("CREATES", savedAssertions.get(0).getPredicate());
        assertEquals("Noesis", savedAssertions.get(0).getSubject());

        // 4. Verify invalid predicate caught & pending assertion cached
        List<FailedPredicateEntity> failedPreds = failedPredicateRepository.findAll();
        assertEquals(1, failedPreds.size());
        assertEquals("INHERITS_FROM", failedPreds.get(0).getName());
        assertEquals(1, failedPreds.get(0).getOccurrenceCount());

        List<PendingAssertionEntity> pendingAssertions = pendingAssertionRepository.findAll();
        assertEquals(1, pendingAssertions.size());
        assertEquals("INHERITS_FROM", pendingAssertions.get(0).getPredicate());
        assertEquals("Noesis", pendingAssertions.get(0).getSubject());

        // 5. Approve unrecognized predicate
        predicateService.approvePredicate("INHERITS_FROM");

        // 6. Verify pending assertion reprocessed at Zero LLM Cost
        List<AssertionEntity> savedAssertionsAfterApprove = assertionJpaRepository.findAll();
        assertEquals(2, savedAssertionsAfterApprove.size()); // Both assertions are now persisted!
        
        boolean foundInherits = savedAssertionsAfterApprove.stream()
                .anyMatch(a -> "INHERITS_FROM".equals(a.getPredicate()));
        assertTrue(foundInherits, "Should have successfully reprocessed and saved the INHERITS_FROM assertion");

        // 7. Verify curation cleanup
        assertEquals(0, failedPredicateRepository.count(), "Curation board list should be empty");
        assertEquals(0, pendingAssertionRepository.count(), "Pending assertions table should be clean");
    }

    @Test
    @Transactional
    public void testMisspelledPredicateIsCollapsedToActive() throws Exception {
        // Direct test: call recordFailedPredicate with a near-miss predicate
        AssertionExtractionResponse dto = new AssertionExtractionResponse();
        dto.setSubjectText("System");
        dto.setPredicate("IMPLEMENTES");
        dto.setObjectText("pipeline");
        dto.setRawText("System implementes pipeline");
        dto.setNormalizedText("System IMPLEMENTES pipeline");
        dto.setAttributes(new HashMap<>());

        predicateService.recordFailedPredicate(
                "IMPLEMENTES", "test-doc.md", dto.getRawText(),
                dto, chunkId, UUID.fromString(documentId), 1, runId
        );

        // Then: assertion is saved directly with the corrected predicate
        List<AssertionEntity> savedAssertions = assertionJpaRepository.findAll();
        assertEquals(1, savedAssertions.size(), "Collapsed assertion should be saved as a real assertion");
        assertEquals("IMPLEMENTS", savedAssertions.get(0).getPredicate(),
                "Predicate should be corrected from IMPLEMENTES to IMPLEMENTS");
        assertEquals("System", savedAssertions.get(0).getSubject());

        // And: no failed or pending records were created
        assertEquals(0, failedPredicateRepository.count(),
                "Misspelling should not create a failed_predicate entry");
        assertEquals(0, pendingAssertionRepository.count(),
                "Misspelling should not create a pending_assertion entry");
    }

    @Test
    @Transactional
    public void testNonMisspelledPredicateStillGoesToFailed() throws Exception {
        // Given: a genuinely novel predicate (INHERITS_FROM) not in active set and
        // not a near-miss of any active predicate

        AssertionExtractionResponse assertion = new AssertionExtractionResponse();
        assertion.setSubjectText("Noesis");
        assertion.setPredicate("INHERITS_FROM");
        assertion.setObjectText("database");
        assertion.setRawText("inherits_from database");
        assertion.setNormalizedText("Noesis inherits_from database");
        assertion.setAttributes(new HashMap<>());

        when(llmClient.extractAssertions(anyString(), anyString())).thenReturn(List.of(assertion));

        // When
        assertionExtractionService.processDocumentAssertions(documentId);

        // Then: no real assertion saved
        assertEquals(0, assertionJpaRepository.count(),
                "Unknown predicate should not create a real assertion");

        // And: failed + pending records created
        assertEquals(1, failedPredicateRepository.count());
        assertEquals("INHERITS_FROM", failedPredicateRepository.findAll().get(0).getName());
        assertEquals(1, pendingAssertionRepository.count());
        assertEquals("INHERITS_FROM", pendingAssertionRepository.findAll().get(0).getPredicate());
    }

    @Test
    @Transactional
    public void testCollapsedAssertionIncrementsDocumentCount() throws Exception {
        // Given
        AssertionExtractionResponse assertion = new AssertionExtractionResponse();
        assertion.setSubjectText("System");
        assertion.setPredicate("IMPLEMENTES");
        assertion.setObjectText("pipeline");
        assertion.setRawText("System implementes pipeline");
        assertion.setNormalizedText("System IMPLEMENTES pipeline");
        assertion.setAttributes(new HashMap<>());

        when(llmClient.extractAssertions(anyString(), anyString())).thenReturn(List.of(assertion));

        // When
        assertionExtractionService.processDocumentAssertions(documentId);

        // Verify the LLM was actually called
        org.mockito.Mockito.verify(llmClient, org.mockito.Mockito.atLeastOnce())
                .extractAssertions(anyString(), anyString());

        // Then
        List<AssertionEntity> savedAssertions = assertionJpaRepository.findAll();
        assertEquals(1, savedAssertions.size(), "Collapsed assertion should be saved");
        assertEquals("IMPLEMENTS", savedAssertions.get(0).getPredicate());
    }
}
