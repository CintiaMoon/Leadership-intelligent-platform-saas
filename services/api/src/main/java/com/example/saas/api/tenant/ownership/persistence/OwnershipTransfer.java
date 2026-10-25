package com.example.saas.api.tenant.ownership.persistence;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.example.saas.api.tenant.ownership.domain.TransferResolution;
import com.example.saas.api.tenant.ownership.domain.TransferStatus;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A request to hand a company's ownership to another active member (SPEC-OWN). Never serialized to an API response:
 * controllers use DTOs. It holds identifiers only. Changing the Owner itself is done on the memberships, in the same
 * transaction as {@link #complete}; this row records the request.
 */
@Entity
@Table(name = "ownership_transfer")
public class OwnershipTransfer {

	/** A transfer expires 7 days after it was initiated (D-30). */
	public static final Duration LIFETIME = Duration.ofDays(7);

	@Id
	private UUID id;

	@Column(name = "company_id", nullable = false, updatable = false)
	private UUID companyId;

	@Column(name = "initiated_by", nullable = false, updatable = false)
	private UUID initiatedBy;

	@Column(name = "recipient_id", nullable = false, updatable = false)
	private UUID recipientId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private TransferStatus status;

	@Convert(converter = ResolutionConverter.class)
	private TransferResolution resolution;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "expires_at", nullable = false, updatable = false)
	private Instant expiresAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "resolved_at")
	private Instant resolvedAt;

	@Column(name = "resolved_by")
	private UUID resolvedBy;

	@Version
	private long version;

	protected OwnershipTransfer() {
	}

	/** A new PENDING transfer that expires {@link #LIFETIME} after {@code now}. */
	public static OwnershipTransfer initiate(UUID companyId, UUID initiatedBy, UUID recipientId, Instant now) {
		OwnershipTransfer transfer = new OwnershipTransfer();
		transfer.id = UUID.randomUUID();
		transfer.companyId = companyId;
		transfer.initiatedBy = initiatedBy;
		transfer.recipientId = recipientId;
		transfer.status = TransferStatus.PENDING;
		transfer.createdAt = now;
		transfer.expiresAt = now.plus(LIFETIME);
		transfer.updatedAt = now;
		return transfer;
	}

	/** The recipient accepted and the memberships were changed in the same transaction. */
	public void complete(Instant now) {
		resolve(TransferResolution.ACCEPTED, recipientId, now);
	}

	/** The initiator withdrew it. */
	public void cancelByInitiator(Instant now) {
		resolve(TransferResolution.CANCELLED_BY_INITIATOR, initiatedBy, now);
	}

	/** A deletion request ended it (D-31); {@code by} is whoever requested the deletion. */
	public void cancelForDeletion(UUID by, Instant now) {
		resolve(TransferResolution.CANCELLED_COMPANY_DELETION, by, now);
	}

	/** It ran past its expiry. Nobody resolved it. */
	public void expire(Instant now) {
		resolve(TransferResolution.EXPIRED, null, now);
	}

	private void resolve(TransferResolution how, UUID by, Instant now) {
		if (status != TransferStatus.PENDING) {
			throw new IllegalStateException("The ownership transfer is not pending");
		}
		this.status = how.status();
		this.resolution = how;
		this.resolvedAt = now;
		this.resolvedBy = by;
		this.updatedAt = now;
	}

	/** Whether this pending transfer has run past its expiry at {@code now}. A transfer that is not pending is never overdue. */
	public boolean isOverdue(Instant now) {
		return status == TransferStatus.PENDING && !expiresAt.isAfter(now);
	}

	/**
	 * The status a reader sees: an overdue pending transfer is EXPIRED before the sweep has marked it (AC-OWN-16). Reading
	 * never writes.
	 */
	public TransferStatus effectiveStatus(Instant now) {
		return isOverdue(now) ? TransferStatus.EXPIRED : status;
	}

	public UUID getId() {
		return id;
	}

	public UUID getCompanyId() {
		return companyId;
	}

	public UUID getInitiatedBy() {
		return initiatedBy;
	}

	public UUID getRecipientId() {
		return recipientId;
	}

	public TransferStatus getStatus() {
		return status;
	}

	public TransferResolution getResolution() {
		return resolution;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public Instant getResolvedAt() {
		return resolvedAt;
	}

	public UUID getResolvedBy() {
		return resolvedBy;
	}

	/** Maps the resolution to the lower-case code the migration checks, and back. */
	@Converter
	public static class ResolutionConverter implements AttributeConverter<TransferResolution, String> {

		@Override
		public String convertToDatabaseColumn(TransferResolution resolution) {
			return resolution == null ? null : resolution.code();
		}

		@Override
		public TransferResolution convertToEntityAttribute(String code) {
			return code == null ? null : TransferResolution.ofCode(code);
		}

	}

}
