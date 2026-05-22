package com.noesis.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphComponentEvent {
    private String eventType;
    private String ingestionRunId;
    private String documentId;
    private String assertionId;
    private String subjectNodeId;
    private String objectNodeId;
    private String edgeId;
}
