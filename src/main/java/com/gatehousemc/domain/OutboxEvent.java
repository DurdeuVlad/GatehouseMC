package com.gatehousemc.domain;

import java.time.Instant;
import java.util.UUID;

public record OutboxEvent(
        UUID id,
        String eventType,
        UUID aggregateId,
        String payloadJson,
        OutboxState state,
        int attempts,
        Instant availableAt,
        Instant createdAt,
        Instant updatedAt,
        String lastError
) {
    public enum OutboxState { READY, PROCESSING, COMPLETE }
}
