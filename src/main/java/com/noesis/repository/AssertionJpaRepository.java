package com.noesis.repository;

import com.noesis.entity.AssertionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AssertionJpaRepository extends JpaRepository<AssertionEntity, UUID> {
    List<AssertionEntity> findByIngestionRunId(UUID ingestionRunId);
    List<AssertionEntity> findByDocumentId(UUID documentId);
    List<AssertionEntity> findByPredicate(String predicate);

    @Query("SELECT a.semanticChecksum FROM AssertionEntity a WHERE a.documentId = :documentId")
    List<String> findChecksumsByDocumentId(UUID documentId);
}
