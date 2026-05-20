package com.noesis.sgms.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndexCompletionEvent {
    private String eventType;
    private String ingestionRunId;
    private String documentId;
    private List<String> assertionIds;
    private List<String> edgeIds;
}
