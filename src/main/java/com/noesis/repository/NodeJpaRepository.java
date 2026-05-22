package com.noesis.repository;

import com.noesis.entity.NodeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface NodeJpaRepository extends JpaRepository<NodeEntity, UUID> {
    Optional<NodeEntity> findBySemanticChecksum(String semanticChecksum);
    java.util.List<NodeEntity> findBySemanticChecksumIn(java.util.Collection<String> semanticChecksums);
}
