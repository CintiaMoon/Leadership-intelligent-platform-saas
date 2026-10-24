package com.example.saas.api.invitation.persistence;

import java.time.Instant;
import java.util.UUID;

import com.example.saas.api.invitation.domain.InvitationStatus;
import com.example.saas.api.tenant.domain.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * An invitation to join one company (SPEC-INV). Never serialized to an API response: controllers use DTOs. It holds the
 * invited address, so it is deleted with the company (slice 007). It never holds a raw token, only a hash, and only
 * while it is pending and delivered.
 */
@Entity
@Table(name = "invitation")
public class Invitation {

	@Id
	private UUID id;

	@Column(name = "company_id", nullable = false, updatable = false)
	private UUID companyId;

	@Column(name = "email_normalized", nullable = false, updatable = false)
	private String emailNormalized;

	@Column(name = "email_original", nullable = false, updatable = false)
	private String emailOriginal;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Role role;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private InvitationStatus status;

	@Column(name = "token_hash")
	private String tokenHash;

	@Column(name = "invited_by", nullable = false)
	private UUID invitedBy;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "accepted_at")
	private Instant acceptedAt;

	@Column(name = "accepted_by")
	private UUID acceptedBy;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	@Column(name = "revoked_by")
	private UUID revokedBy;

	@Version
	private long version;

	protected Invitation() {
	}

	/**
	 * A new PENDING invitation with no token yet: delivery mints it (D-146). The role is never Owner; the database
	 * refuses it too.
	 */
	public static Invitation issue(UUID companyId, String emailNormalized, String emailOriginal, Role role,
			UUID invitedBy, Instant now, Instant expiresAt) {
		Invitation invitation = new Invitation();
		invitation.id = UUID.randomUUID();
		invitation.companyId = companyId;
		invitation.emailNormalized = emailNormalized;
		invitation.emailOriginal = emailOriginal;
		invitation.role = role;
		invitation.status = InvitationStatus.PENDING;
		invitation.invitedBy = invitedBy;
		invitation.expiresAt = expiresAt;
		invitation.createdAt = now;
		invitation.updatedAt = now;
		return invitation;
	}

	public UUID getId() {
		return id;
	}

	public UUID getCompanyId() {
		return companyId;
	}

	public String getEmailNormalized() {
		return emailNormalized;
	}

	public String getEmailOriginal() {
		return emailOriginal;
	}

	public Role getRole() {
		return role;
	}

	public InvitationStatus getStatus() {
		return status;
	}

	public String getTokenHash() {
		return tokenHash;
	}

	public UUID getInvitedBy() {
		return invitedBy;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getAcceptedAt() {
		return acceptedAt;
	}

	public UUID getAcceptedBy() {
		return acceptedBy;
	}

	public Instant getRevokedAt() {
		return revokedAt;
	}

	public UUID getRevokedBy() {
		return revokedBy;
	}

}
