package com.noesis.client;

import com.noesis.service.NoesisConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;

@Slf4j
@Component
@RequiredArgsConstructor
public class LlmRateLimiter {

    private final NoesisConfigService configService;
    private final Deque<Long> window = new ConcurrentLinkedDeque<>();

    public void acquire() {
        LlmSettings.RateLimiterConfig rateLimiterConfig = configService.getLlmSettings().getRateLimiter();
        if (!rateLimiterConfig.isEnabled()) return;

        int maxCallsPerMinute = rateLimiterConfig.getMaxCallsPerMinute();

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
