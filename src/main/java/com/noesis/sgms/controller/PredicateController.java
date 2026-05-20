package com.noesis.sgms.controller;

import com.noesis.sgms.entity.ActivePredicateEntity;
import com.noesis.sgms.entity.FailedPredicateEntity;
import com.noesis.sgms.repository.ActivePredicateRepository;
import com.noesis.sgms.repository.FailedPredicateRepository;
import com.noesis.sgms.service.PredicateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/predicates")
@RequiredArgsConstructor
public class PredicateController {

    private final PredicateService predicateService;
    private final FailedPredicateRepository failedPredicateRepository;
    private final ActivePredicateRepository activePredicateRepository;

    @GetMapping("/active")
    public ResponseEntity<List<com.noesis.sgms.entity.ActivePredicateEntity>> getActivePredicates() {
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
}
