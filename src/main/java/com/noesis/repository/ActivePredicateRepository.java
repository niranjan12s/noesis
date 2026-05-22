package com.noesis.repository;

import com.noesis.entity.ActivePredicateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ActivePredicateRepository extends JpaRepository<ActivePredicateEntity, String> {
}
