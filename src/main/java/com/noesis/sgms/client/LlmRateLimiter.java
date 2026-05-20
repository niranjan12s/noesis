package com.noesis.sgms.client;

import com.noesis.sgms.config.SgmsProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;

@Slf4j
@Component
@RequiredArgsConstructor
public class LlmRateLimiter {

    private final SgmsProperties sgmsProperties;

    private boolean enabled;
    private int maxCallsPerMinute;
    private final Deque<Long> window = new ConcurrentLinkedDeque<>();

    @PostConstruct
    public void init() {
        this.enabled = sgmsProperties.getLlm().getRateLimiter().isEnabled();
        this.maxCallsPerMinute = sgmsProperties.getLlm().getRateLimiter().getMaxCallsPerMinute();
        if (enabled) {
            log.info("LLM rate limiter enabled: max {} calls/minute", maxCallsPerMinute);
        }
    }

    public void acquire() {
        if (!enabled) return;

        long now = System.currentTimeMillis();
        synchronized (window) {
            while (true) {
                long cutoff = now - 60_000;
                while (!window.isEmpty() && window.peekFirst() < cutoff) {
                    window.pollFirst();
                }

                if (window.size() < maxCallsPerMinute) {
                    window.addLast(now);
                    return;
                }

                long oldest = window.peekFirst();
                long waitMs = oldest + 60_001 - now;
                if (waitMs > 0) {
                    log.debug("Rate limit reached ({} calls/min). Waiting {}ms before next call.", maxCallsPerMinute, waitMs);
                    try {
                        window.wait(waitMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    now = System.currentTimeMillis();
                }
            }
        }
    }
}
