package com.noesis.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * Idempotent predicate ontology seeder.
 *
 * Runs once at application startup (after JPA schema creation) and executes
 * sql/seed-active-predicates.sql against the live DataSource.  The SQL uses
 * INSERT … ON CONFLICT DO NOTHING so it is safe on every boot — it only adds
 * rows that are genuinely missing and never overwrites anything a user changed.
 *
 * After seeding the DB, it tells PredicateService to rebuild its Redis caches
 * (active predicates + aliases) so the hot ingestion path is warm before the
 * first file event fires.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PredicateSeedService {

    private final DataSource dataSource;
    private final PredicateService predicateService;

    @PostConstruct
    public void seed() {
        try (Connection conn = dataSource.getConnection()) {
            ClassPathResource script = new ClassPathResource("sql/seed-active-predicates.sql");
            ScriptUtils.executeSqlScript(conn, script);
            log.info("Active predicate seed script executed (ON CONFLICT DO NOTHING — safe to re-run).");
        } catch (Exception e) {
            log.error("Failed to execute predicate seed script. Ingestion will continue but " +
                      "the active predicate list may be empty on a fresh DB.", e);
        }

        // Rebuild Redis caches from the (now-seeded) DB
        predicateService.reloadAllCaches();
    }
}
