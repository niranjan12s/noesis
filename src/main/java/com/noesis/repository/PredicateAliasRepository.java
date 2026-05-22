package com.noesis.repository;

import com.noesis.entity.PredicateAliasEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PredicateAliasRepository extends JpaRepository<PredicateAliasEntity, String> {
    // findById(source) covers the single-alias lookup via JpaRepository default
}
