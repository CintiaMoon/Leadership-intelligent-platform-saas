package com.example.saas.api.auth.outbox;

import java.util.Map;
import java.util.UUID;

/** A claimed unit of work. The payload carries identifiers only, never secrets. */
public record OutboxEvent(UUID id, String eventType, Map<String, String> payload, int attempts, int maxAttempts) {
}
