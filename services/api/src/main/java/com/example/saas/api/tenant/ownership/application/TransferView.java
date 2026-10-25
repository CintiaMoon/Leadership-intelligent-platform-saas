package com.example.saas.api.tenant.ownership.application;

import java.time.Instant;
import java.util.UUID;

import com.example.saas.api.tenant.ownership.domain.TransferStatus;

/**
 * What a party to a transfer may see (AC-OWN-22): the status as a reader sees it (an overdue pending transfer is
 * EXPIRED), the two display names, who the caller is, and the dates. No user ID and no address of the other party.
 */
public record TransferView(UUID id, UUID companyId, TransferStatus status, String initiatorName, String recipientName,
		boolean youAreInitiator, boolean youAreRecipient, Instant createdAt, Instant expiresAt, Instant resolvedAt) {
}
