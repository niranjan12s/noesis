package com.noesis.controller;

import com.noesis.entity.ActivePredicateEntity;
import com.noesis.entity.FailedPredicateEntity;
import com.noesis.repository.ActivePredicateRepository;
import com.noesis.repository.FailedPredicateRepository;
import com.noesis.service.PredicateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/predicates")
@RequiredArgsConstructor
public class PredicateController {

    private final PredicateService predicateService;
    private final FailedPredicateRepository failedPredicateRepository;
    private final ActivePredicateRepository activePredicateRepository;

    @GetMapping("/active")
    public ResponseEntity<List<com.noesis.entity.ActivePredicateEntity>> getActivePredicates() {
        return ResponseEntity.ok(activePredicateRepository.findAll());
    }

    @GetMapping("/failed")
    public ResponseEntity<List<FailedPredicateEntity>> getFailedPredicates() {
        List<FailedPredicateEntity> list = failedPredicateRepository.findAll();
        // Sort by occurrence count descending, then by name
        list.sort((a, b) -> {
            int cmp = b.getOccurrenceCount().compareTo(a.getOccurrenceCount());
            if (cmp != 0) return cmp;
            return a.getName().compareTo(b.getName());
        });
        return ResponseEntity.ok(list);
    }

    @PostMapping("/approve")
    public ResponseEntity<String> approvePredicate(@RequestParam String name) {
        log.info("REST request to approve predicate: '{}'", name);
        try {
            predicateService.approvePredicate(name);
            return ResponseEntity.ok("Approved and reprocessed successfully: " + name);
        } catch (Exception e) {
            log.error("Failed to approve predicate: {}", name, e);
            return ResponseEntity.internalServerError().body("Approval failed: " + e.getMessage());
        }
    }

    @PostMapping("/reject")
    public ResponseEntity<String> rejectPredicate(@RequestParam String name) {
        log.info("REST request to reject predicate: '{}'", name);
        try {
            predicateService.rejectPredicate(name);
            return ResponseEntity.ok("Rejected and cleaned up successfully: " + name);
        } catch (Exception e) {
            log.error("Failed to reject predicate: {}", name, e);
            return ResponseEntity.internalServerError().body("Rejection failed: " + e.getMessage());
        }
    }

    @PostMapping("/revoke")
    public ResponseEntity<String> revokePredicate(@RequestParam String name) {
        log.info("REST request to revoke predicate: '{}'", name);
        try {
            predicateService.revokePredicate(name);
            return ResponseEntity.ok("Revoked and cleaned up successfully: " + name);
        } catch (Exception e) {
            log.error("Failed to revoke predicate: {}", name, e);
            return ResponseEntity.internalServerError().body("Revoke failed: " + e.getMessage());
        }
    }

    @PostMapping("/auto-approve")
    public ResponseEntity<Map<String, Object>> autoApprovePredicates(@RequestBody Map<String, Integer> body) {
        int threshold = body.getOrDefault("threshold", 3);
        log.info("REST request to auto-approve predicates with threshold >= {}", threshold);
        try {
            int count = predicateService.autoApprovePredicates(threshold);
            Map<String, Object> response = new java.util.HashMap<>();
            response.put("approvedCount", count);
            response.put("threshold", threshold);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to auto-approve predicates", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Maps a failed predicate to an existing (or new) active predicate and reprocesses
     * all pending assertions under the new name.  The mapping is persisted as an alias
     * so future occurrences of failedName are resolved silently from the Redis cache.
     */
    @PostMapping("/map")
    public ResponseEntity<String> mapPredicate(
            @RequestParam String failedName,
            @RequestParam String targetName) {
        log.info("REST request to map predicate '{}' → '{}'", failedName, targetName);
        try {
            predicateService.mapAndReprocessPredicate(failedName, targetName);
            return ResponseEntity.ok("Mapped and reprocessed successfully: " + failedName + " → " + targetName);
        } catch (Exception e) {
            log.error("Failed to map predicate '{}' → '{}'", failedName, targetName, e);
            return ResponseEntity.internalServerError().body("Map failed: " + e.getMessage());
        }
    }

    /** Returns the full alias table for UI inspection. */
    @GetMapping("/aliases")
    public ResponseEntity<List<com.noesis.entity.PredicateAliasEntity>> getAliases() {
        return ResponseEntity.ok(predicateService.getAllAliases());
    }
}

