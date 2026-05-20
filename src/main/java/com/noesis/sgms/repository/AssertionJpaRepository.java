package com.noesis.sgms.repository;

import com.noesis.sgms.entity.AssertionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AssertionJpaRepository extends JpaRepository<AssertionEntity, UUID> {
    List<AssertionEntity> findByIngestionRunId(UUID ingestionRunId);
    List<AssertionEntity> findByDocumentId(UUID documentId);
}
