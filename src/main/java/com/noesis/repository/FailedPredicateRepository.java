package com.noesis.repository;

import com.noesis.entity.FailedPredicateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FailedPredicateRepository extends JpaRepository<FailedPredicateEntity, String> {
    List<FailedPredicateEntity> findByOccurrenceCountGreaterThanEqual(int minCount);
}
