package com.noesis.repository;

import com.noesis.entity.DocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface DocumentRepository extends JpaRepository<DocumentEntity, String> {
    Optional<DocumentEntity> findByAbsolutePath(String absolutePath);
    List<DocumentEntity> findByMarkedForDeletionAtBefore(Instant threshold);
}
