package com.example.saas.api.tenant.deletion.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Published inside the transaction that records a deletion request, so a listener annotated
 * {@code @TransactionalEventListener} (after commit) sees it only once the request is durable. Nothing listens yet: the
 * billing slice will cancel the company's subscription here (plan Q-38).
 */
public record CompanyDeletionRequested(UUID companyId, UUID requestedBy, Instant requestedAt, Instant purgeAt) {
}
