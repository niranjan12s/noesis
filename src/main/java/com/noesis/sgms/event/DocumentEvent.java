package com.noesis.sgms.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentEvent {
    private String eventId;
    private String documentId;
    private String ingestionRunId;
    private DocumentEventType eventType;
    private Integer version;
    private Instant timestamp;
}
