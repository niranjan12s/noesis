package com.noesis.repository;

import com.noesis.entity.IngestionRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface IngestionRunRepository extends JpaRepository<IngestionRunEntity, UUID> {
    List<IngestionRunEntity> findByDocumentId(UUID documentId);
}
