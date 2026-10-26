package com.example.saas.api.tenant.ownership.web;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.example.saas.api.tenant.ownership.application.TransferView;
import com.example.saas.api.tenant.ownership.domain.TransferStatus;

/**
 * An ownership transfer as the API returns it: no user ID, no address, no token. {@code resolvedAt} is left out until the
 * transfer is resolved, as the contract says.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record OwnershipTransferResponse(UUID id, TransferStatus status, String initiatorName, String recipientName,
		boolean youAreInitiator, boolean youAreRecipient, Instant createdAt, Instant expiresAt, Instant resolvedAt) {

	static OwnershipTransferResponse of(TransferView view) {
		return new OwnershipTransferResponse(view.id(), view.status(), view.initiatorName(), view.recipientName(),
				view.youAreInitiator(), view.youAreRecipient(), view.createdAt(), view.expiresAt(), view.resolvedAt());
	}

}
