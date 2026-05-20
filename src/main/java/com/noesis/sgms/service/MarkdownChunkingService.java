package com.noesis.sgms.service;

import com.noesis.sgms.config.KafkaTopics;
import com.noesis.sgms.entity.ChunkEntity;
import com.noesis.sgms.entity.DocumentEntity;
import com.noesis.sgms.entity.DocumentStatus;
import com.noesis.sgms.event.DocumentEvent;
import com.noesis.sgms.event.DocumentEventType;
import com.noesis.sgms.events.ChunkCreatedEvent;
import com.noesis.sgms.producer.BulkChunkProducer;
import com.noesis.sgms.producer.DocumentEventProducer;
import com.noesis.sgms.repository.ChunkJpaRepository;
import com.noesis.sgms.repository.DocumentRepository;
import com.noesis.sgms.event.PipelineEvent;
import org.springframework.context.ApplicationEventPublisher;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.IndentedCodeBlock;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Slf4j
@Service
public class MarkdownChunkingService {

    private static final EncodingRegistry JTOKKIT_REGISTRY = Encodings.newDefaultEncodingRegistry();
    private static final Encoding JTOKKIT_ENC = JTOKKIT_REGISTRY.getEncodingForModel(com.knuddels.jtokkit.api.ModelType.GPT_4O_MINI);

    private final DocumentRepository documentRepository;
    private final ChunkJpaRepository chunkJpaRepository;
    private final DocumentEventProducer documentEventProducer;
    private final NeosisStateService neosisStateService;
    private final ApplicationEventPublisher eventPublisher;
    private final ModeService modeService;
    private final BulkChunkProducer bulkChunkProducer;

    private final Parser parser;

    public MarkdownChunkingService(DocumentRepository documentRepository, ChunkJpaRepository chunkJpaRepository, DocumentEventProducer documentEventProducer, NeosisStateService neosisStateService, ApplicationEventPublisher eventPublisher, ModeService modeService, BulkChunkProducer bulkChunkProducer) {
        this.documentRepository = documentRepository;
        this.chunkJpaRepository = chunkJpaRepository;
        this.documentEventProducer = documentEventProducer;
        this.neosisStateService = neosisStateService;
        this.eventPublisher = eventPublisher;
        this.modeService = modeService;
        this.bulkChunkProducer = bulkChunkProducer;
        MutableDataSet options = new MutableDataSet();
        this.parser = Parser.builder(options).build();
    }

    @Transactional
    public void processDocumentChunking(String documentIdStr) {
        log.info("Starting chunking process for document: {}", documentIdStr);
        DocumentEntity document = documentRepository.findById(documentIdStr).orElse(null);

        if (document == null) {
            log.error("Document not found: {}", documentIdStr);
            return;
        }

        String relativePath = neosisStateService.getRelativePathString(document.getAbsolutePath());

        try {
            document.setStatus(DocumentStatus.PROCESSING_ASSERTIONS);
            documentRepository.save(document);

            // Update SQLite state
            neosisStateService.upsertDocumentState(
                    relativePath,
                    DocumentStatus.PROCESSING_ASSERTIONS,
                    "CHUNKING",
                    0,
                    null,
                    null,
                    "",
                    0,
                    document.getChecksum()
            );

            Path filePath = Paths.get(document.getAbsolutePath());
            if (!Files.exists(filePath)) {
                throw new RuntimeException("File does not exist: " + filePath);
            }

            String markdown = Files.readString(filePath);
            Node documentNode = parser.parse(markdown);

            List<ChunkEntity> chunks = extractChunks(documentNode, document);
            chunkJpaRepository.saveAll(chunks);

            // Update SQLite with total chunks
            neosisStateService.upsertDocumentState(
                    relativePath,
                    DocumentStatus.PROCESSING_ASSERTIONS,
                    "CHUNKING_COMPLETED",
                    0,
                    null,
                    null,
                    "",
                    chunks.size(),
                    document.getChecksum()
            );

            emitEvent(document, DocumentEventType.CHUNKING_COMPLETED);
            log.info("Successfully completed chunking for document: {}", documentIdStr);

            // Trigger downstream in-process step synchronously
            eventPublisher.publishEvent(PipelineEvent.builder()
                    .eventType("CHUNKING_COMPLETED")
                    .documentId(document.getId())
                    .ingestionRunId(document.getIngestionRunId())
                    .build());

            // In bulk mode, produce each chunk to the Kafka topic instead
            if ("bulk".equals(modeService.getCurrentMode()) && modeService.isBulkJobActive()) {
                for (ChunkEntity chunk : chunks) {
                    bulkChunkProducer.sendChunkCreated(ChunkCreatedEvent.builder()
                            .chunkId(chunk.getId().toString())
                            .documentId(chunk.getDocumentId().toString())
                            .sectionPath(chunk.getSectionPath())
                            .text(chunk.getNormalizedContent())
                            .orderIndex(chunk.getSequenceNumber() != null ? chunk.getSequenceNumber() : 0)
                            .createdAt(System.currentTimeMillis())
                            .build());
                }
                log.info("Published {} chunks to {} for bulk mode", chunks.size(), KafkaTopics.CHUNK_CREATED_EVENTS);
            }

        } catch (Exception e) {
            log.error("Chunking failed for document: {}", documentIdStr, e);
            document.setStatus(DocumentStatus.FAILED_FATAL);
            documentRepository.save(document);

            // Update SQLite with fatal status
            neosisStateService.upsertDocumentState(
                    relativePath,
                    DocumentStatus.FAILED_FATAL,
                    "CHUNKING_FAILED",
                    0,
                    e.getMessage(),
                    null,
                    "",
                    0,
                    document.getChecksum()
            );

            emitEvent(document, DocumentEventType.CHUNKING_FAILED);
        }
    }

    private List<ChunkEntity> extractChunks(Node rootNode, DocumentEntity document) throws Exception {
        List<ChunkEntity> chunks = new ArrayList<>();
        Map<Integer, String> hierarchy = new TreeMap<>();
        
        StringBuilder currentContent = new StringBuilder();
        String currentHeading = "";
        int currentSequence = 1;
        
        Node child = rootNode.getFirstChild();
        while (child != null) {
            if (child instanceof Heading) {
                Heading headingNode = (Heading) child;
                int level = headingNode.getLevel();
                String text = headingNode.getText().toString();

                // Save previous chunk if it has content
                if (currentContent.length() > 0) {
                    chunks.add(buildChunk(document, hierarchy, currentHeading, currentContent.toString(), currentSequence++));
                    currentContent = new StringBuilder();
                }

                // Update hierarchy
                hierarchy.put(level, text);
                // Remove deeper levels
                hierarchy.keySet().removeIf(k -> k > level);
                
                currentHeading = text;
                currentContent.append(child.getChars().toString()).append("\n\n");

            } else {
                if (child instanceof Paragraph || child instanceof FencedCodeBlock || child instanceof IndentedCodeBlock) {
                    currentContent.append(child.getChars().toString()).append("\n\n");
                } else if (child.getChars() != null && !child.getChars().isEmpty()) {
                    currentContent.append(child.getChars().toString()).append("\n\n");
                }
            }
            child = child.getNext();
        }

        if (currentContent.length() > 0) {
            chunks.add(buildChunk(document, hierarchy, currentHeading, currentContent.toString(), currentSequence));
        }

        return chunks;
    }

    private ChunkEntity buildChunk(DocumentEntity document, Map<Integer, String> hierarchy, String heading, String content, int sequenceNumber) throws Exception {
        String sectionPath = String.join(">", hierarchy.values());
        if (sectionPath.isEmpty()) {
            sectionPath = "Document";
        }
        
        String normalizedContent = content.toLowerCase()
                .replaceAll("(?m)^#+\\s*(.*)$", "$1") // Strip markdown headings
                .replaceAll("(?s)```.*?\\n(.*?)```", "$1") // Strip code blocks syntax
                .replaceAll("[*`_~>\\-\\[\\]]", "") // Strip other markdown chars
                .replaceAll("\\s+", " ") // Whitespace cleanup
                .trim();

        String checksumInput = sectionPath + content;
        String chunkChecksum = computeChecksum(checksumInput);

        return ChunkEntity.builder()
                .id(UUID.randomUUID())
                .documentId(UUID.fromString(document.getId()))
                .documentVersion(document.getVersion())
                .sectionPath(sectionPath)
                .heading(heading.isEmpty() ? null : heading)
                .content(content.trim())
                .normalizedContent(normalizedContent)
                .chunkChecksum(chunkChecksum)
                .sequenceNumber(sequenceNumber)
                .projectRoot(document.getProjectRoot())
                .tokenEstimate(JTOKKIT_ENC.countTokens(content))
                .build();
    }

    private String computeChecksum(String text) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(text.getBytes("UTF-8"));
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

    private void emitEvent(DocumentEntity doc, DocumentEventType eventType) {
        DocumentEvent event = DocumentEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .documentId(doc.getId())
                .ingestionRunId(doc.getIngestionRunId())
                .eventType(eventType)
                .version(doc.getVersion())
                .timestamp(Instant.now())
                .build();
                
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        documentEventProducer.sendDocumentEvent(event);
                    }
                }
            );
        } else {
            documentEventProducer.sendDocumentEvent(event);
        }
    }
}
