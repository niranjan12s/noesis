package com.noesis.service;

import com.noesis.entity.DocumentEntity;
import com.noesis.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentDeletionService {

    private final DocumentRepository documentRepository;
    private final ChunkJpaRepository chunkRepository;
    private final AssertionJpaRepository assertionRepository;
    private final PendingAssertionRepository pendingAssertionRepository;
    private final EdgeJpaRepository edgeRepository;
    private final NodeJpaRepository nodeRepository;
    private final IngestionRunRepository ingestionRunRepository;

    @Transactional
    public void deleteDocumentData(DocumentEntity doc) {
        try {
            UUID docUuid = UUID.fromString(doc.getId());

            List<com.noesis.entity.IngestionRunEntity> runs = ingestionRunRepository.findByDocumentId(docUuid);
            List<UUID> runIds = runs.stream().map(com.noesis.entity.IngestionRunEntity::getId).toList();

            assertionRepository.deleteAll(assertionRepository.findByDocumentId(docUuid));
            pendingAssertionRepository.deleteAll(pendingAssertionRepository.findByDocumentId(docUuid));
            chunkRepository.deleteAll(chunkRepository.findByDocumentId(docUuid));

            Set<UUID> orphanCandidateNodes = new HashSet<>();
            for (UUID runId : runIds) {
                List<com.noesis.entity.EdgeEntity> edges = edgeRepository.findByIngestionRunId(runId);
                for (com.noesis.entity.EdgeEntity e : edges) {
                    orphanCandidateNodes.add(e.getFromNodeId());
                    orphanCandidateNodes.add(e.getToNodeId());
                }
                edgeRepository.deleteAll(edges);
            }

            List<com.noesis.entity.EdgeEntity> remainingEdges = edgeRepository.findAll();
            List<com.noesis.entity.AssertionEntity> remainingAssertions = assertionRepository.findAll();

            for (UUID nid : orphanCandidateNodes) {
                boolean hasEdges = remainingEdges.stream().anyMatch(e ->
                        e.getFromNodeId().equals(nid) || e.getToNodeId().equals(nid));
                boolean hasAssertions = remainingAssertions.stream().anyMatch(a ->
                        nid.equals(a.getSubjectNodeId()) || nid.equals(a.getObjectNodeId()));
                if (!hasEdges && !hasAssertions) {
                    nodeRepository.findById(nid).ifPresent(nodeRepository::delete);
                }
            }

            ingestionRunRepository.deleteAll(runs);
            documentRepository.delete(doc);

            log.info("Hard-deleted document {} and all related data", doc.getName());
        } catch (Exception e) {
            log.error("Failed to hard-delete document {}: {}", doc.getName(), e.getMessage(), e);
        }
    }
}
