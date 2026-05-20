package com.noesis.sgms.repository;

import com.noesis.sgms.entity.EdgeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EdgeJpaRepository extends JpaRepository<EdgeEntity, UUID> {
    Optional<EdgeEntity> findBySemanticChecksum(String semanticChecksum);
    List<EdgeEntity> findByIngestionRunId(UUID ingestionRunId);
    List<EdgeEntity> findBySemanticChecksumIn(java.util.Collection<String> semanticChecksums);
}
