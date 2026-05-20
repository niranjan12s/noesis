package com.noesis.sgms.repository;

import com.noesis.sgms.entity.FailedPredicateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FailedPredicateRepository extends JpaRepository<FailedPredicateEntity, String> {
}
