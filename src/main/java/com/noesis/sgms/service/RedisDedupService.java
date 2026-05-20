package com.noesis.sgms.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisDedupService {

    private final StringRedisTemplate stringRedisTemplate;

    private static final String CHECKSUM_PREFIX = "neosis:dedup:checksum:";
    private static final String STATE_PREFIX = "neosis:dedup:state:";
    private static final Duration DEDUP_TTL = Duration.ofHours(24);

    public boolean isChecksumKnown(String checksum) {
        String val = stringRedisTemplate.opsForValue().get(CHECKSUM_PREFIX + checksum);
        return val != null;
    }

    public void recordChecksum(String checksum, String documentId) {
        stringRedisTemplate.opsForValue().set(
            CHECKSUM_PREFIX + checksum,
            documentId + "|" + Instant.now().toString(),
            DEDUP_TTL
        );
    }

    public void removeChecksum(String checksum) {
        stringRedisTemplate.delete(CHECKSUM_PREFIX + checksum);
    }

    public void setStage(String documentId, String stage) {
        stringRedisTemplate.opsForValue().set(
            STATE_PREFIX + documentId,
            stage + "|" + Instant.now().toString(),
            DEDUP_TTL
        );
    }

    public String getStage(String documentId) {
        String val = stringRedisTemplate.opsForValue().get(STATE_PREFIX + documentId);
        if (val == null) return null;
        return val.split("\\|")[0];
    }

    public boolean hasStage(String documentId, String stage) {
        String val = getStage(documentId);
        return stage.equals(val);
    }

    public void removeState(String documentId) {
        stringRedisTemplate.delete(STATE_PREFIX + documentId);
    }

    public void clearAll() {
        Set<String> checksumKeys = stringRedisTemplate.keys(CHECKSUM_PREFIX + "*");
        Set<String> stateKeys = stringRedisTemplate.keys(STATE_PREFIX + "*");
        if (checksumKeys != null) stringRedisTemplate.delete(checksumKeys);
        if (stateKeys != null) stringRedisTemplate.delete(stateKeys);
    }
}
