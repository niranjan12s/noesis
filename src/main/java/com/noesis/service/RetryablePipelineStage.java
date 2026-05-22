package com.noesis.service;

public interface RetryablePipelineStage {
    String stageName();
    default int maxRetries() { return 5; }
    default long backoffSeconds(int retryCount) { return 15L * (1L << (retryCount - 1)); }
    void execute(String documentId);
}
