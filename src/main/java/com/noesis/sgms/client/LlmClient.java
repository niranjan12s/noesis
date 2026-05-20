package com.noesis.sgms.client;

import com.noesis.sgms.dto.AssertionExtractionResponse;
import java.util.List;

public interface LlmClient {
    List<AssertionExtractionResponse> extractAssertions(String sectionPath, String chunkContent);
}
