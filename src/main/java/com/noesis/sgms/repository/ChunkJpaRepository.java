package com.noesis.sgms.repository;

import com.noesis.sgms.entity.ChunkEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ChunkJpaRepository extends JpaRepository<ChunkEntity, UUID> {
    List<ChunkEntity> findByDocumentId(UUID documentId);
    List<ChunkEntity> findByDocumentIdAndDocumentVersion(UUID documentId, Integer documentVersion);

    @Query("SELECT COALESCE(SUM(c.tokenEstimate), 0) FROM ChunkEntity c")
    Long sumTokenEstimates();
}

