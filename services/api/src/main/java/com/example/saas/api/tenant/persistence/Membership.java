package com.example.saas.api.tenant.persistence;

import java.time.Instant;
import java.util.UUID;

import com.example.saas.api.tenant.domain.MembershipStatus;
import com.example.saas.api.tenant.domain.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** A user's membership in one company (SPEC-TEN). Never serialized to an API response: controllers use DTOs. */
@Entity
@Table(name = "membership")
public class Membership {

	@Id
	private UUID id;

	@Column(name = "company_id", nullable = false, updatable = false)
	private UUID companyId;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Role role;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private MembershipStatus status;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	/** When and by whom the status last changed (AC-MEM-13); null for a membership that never changed. */
	@Column(name = "status_changed_at")
	private Instant statusChangedAt;

	@Column(name = "status_changed_by")
	private UUID statusChangedBy;

	@Version
	private long version;

	protected Membership() {
	}

	/** A new ACTIVE membership. The role comes from server logic, never from a request. */
	public static Membership activate(UUID companyId, UUID userId, Role role, Instant now) {
		Membership membership = new Membership();
		membership.id = UUID.randomUUID();
		membership.companyId = companyId;
		membership.userId = userId;
		membership.role = role;
		membership.status = MembershipStatus.ACTIVE;
		membership.createdAt = now;
		membership.updatedAt = now;
		return membership;
	}

	public UUID getId() {
		return id;
	}

	public UUID getCompanyId() {
		return companyId;
	}

	public UUID getUserId() {
		return userId;
	}

	public Role getRole() {
		return role;
	}

	public MembershipStatus getStatus() {
		return status;
	}

	public Instant getStatusChangedAt() {
		return statusChangedAt;
	}

	public UUID getStatusChangedBy() {
		return statusChangedBy;
	}

}
