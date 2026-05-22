package com.noesis.repository;

import com.noesis.entity.PendingAssertionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PendingAssertionRepository extends JpaRepository<PendingAssertionEntity, UUID> {
    List<PendingAssertionEntity> findByPredicate(String predicate);
    void deleteByPredicate(String predicate);
    List<PendingAssertionEntity> findByDocumentId(UUID documentId);
}
