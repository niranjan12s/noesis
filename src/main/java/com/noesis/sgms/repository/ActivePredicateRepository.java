package com.noesis.sgms.repository;

import com.noesis.sgms.entity.ActivePredicateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ActivePredicateRepository extends JpaRepository<ActivePredicateEntity, String> {
}
