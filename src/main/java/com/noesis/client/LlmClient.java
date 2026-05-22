package com.noesis.client;

import com.noesis.dto.AssertionExtractionResponse;
import java.util.List;

public interface LlmClient {
    List<AssertionExtractionResponse> extractAssertions(String sectionPath, String chunkContent);
}
