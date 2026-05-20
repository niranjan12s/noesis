package com.noesis.sgms.service;

import com.noesis.sgms.client.LlmClient;
import com.noesis.sgms.dto.AssertionExtractionResponse;
import com.noesis.sgms.entity.*;
import com.noesis.sgms.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
public class DocumentPipelineIntegrationTest {

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

    @BeforeEach
    public void setUp() {
        // Clean database tables to ensure fresh test runs
        assertionJpaRepository.deleteAll();
        pendingAssertionRepository.deleteAll();
        failedPredicateRepository.deleteAll();
        chunkJpaRepository.deleteAll();
        documentRepository.deleteAll();

        // Setup test Active predicates
        activePredicateRepository.deleteAll();
        predicateService.init(); // prepopulate from Enum

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
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        documentRepository.save(document);

        ChunkEntity chunk = ChunkEntity.builder()
                .id(chunkId)
                .documentId(UUID.fromString(documentId))
                .documentVersion(1)
                .sectionPath("Section 1")
                .content("Neosis system creates memory and inherits_from database.")
                .sequenceNumber(0)
                .createdAt(Instant.now())
                .build();
        chunkJpaRepository.save(chunk);
    }

    @Test
    @Transactional
    public void testAssertionPipelineWithUnrecognizedPredicateAndReprocessing() throws Exception {
        // 1. Mock LLM Response: One active predicate, one unrecognized predicate
        AssertionExtractionResponse assertion1 = new AssertionExtractionResponse();
        assertion1.setSubjectText("Neosis");
        assertion1.setPredicate("CREATES");
        assertion1.setObjectText("memory");
        assertion1.setRawText("creates memory");
        assertion1.setNormalizedText("Neosis creates memory");
        assertion1.setAttributes(new HashMap<>());

        AssertionExtractionResponse assertion2 = new AssertionExtractionResponse();
        assertion2.setSubjectText("Neosis");
        assertion2.setPredicate("INHERITS_FROM"); // Unrecognized predicate!
        assertion2.setObjectText("database");
        assertion2.setRawText("inherits_from database");
        assertion2.setNormalizedText("Neosis inherits_from database");
        assertion2.setAttributes(new HashMap<>());

        List<AssertionExtractionResponse> mockedList = Arrays.asList(assertion1, assertion2);
        when(llmClient.extractAssertions(anyString(), anyString())).thenReturn(mockedList);

        // 2. Trigger Ingestion extraction process
        assertionExtractionService.processDocumentAssertions(documentId);

        // 3. Verify valid assertion persisted
        List<AssertionEntity> savedAssertions = assertionJpaRepository.findAll();
        assertEquals(1, savedAssertions.size());
        assertEquals("CREATES", savedAssertions.get(0).getPredicate());
        assertEquals("Neosis", savedAssertions.get(0).getSubject());

        // 4. Verify invalid predicate caught & pending assertion cached
        List<FailedPredicateEntity> failedPreds = failedPredicateRepository.findAll();
        assertEquals(1, failedPreds.size());
        assertEquals("INHERITS_FROM", failedPreds.get(0).getName());
        assertEquals(1, failedPreds.get(0).getOccurrenceCount());

        List<PendingAssertionEntity> pendingAssertions = pendingAssertionRepository.findAll();
        assertEquals(1, pendingAssertions.size());
        assertEquals("INHERITS_FROM", pendingAssertions.get(0).getPredicate());
        assertEquals("Neosis", pendingAssertions.get(0).getSubject());

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
}
