package com.example.saas.api.tenant.deletion.application;

import java.time.Instant;

import com.example.saas.api.tenant.domain.CompanyStatus;

/**
 * What a request or a cancellation returns: the company's status after it and, while a deletion is pending, when it was
 * requested and the earliest moment of the purge. Both instants are null after a cancellation.
 */
public record DeletionView(CompanyStatus status, Instant requestedAt, Instant purgeAt) {
}
