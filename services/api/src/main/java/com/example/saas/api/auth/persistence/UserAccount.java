package com.example.saas.api.auth.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** A user account (SPEC-AUTH). Never serialized to an API response: controllers use DTOs. */
@Entity
@Table(name = "app_user")
public class UserAccount {

	public enum Status {

		ACTIVE, DISABLED

	}

	@Id
	private UUID id;

	@Column(name = "email_normalized", nullable = false, updatable = false)
	private String emailNormalized;

	@Column(name = "email_original", nullable = false)
	private String emailOriginal;

	@Column(name = "display_name", nullable = false)
	private String displayName;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
	@Column(nullable = false)
	private Status status;

	@Column(name = "email_verified_at")
	private Instant emailVerifiedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	private long version;

	protected UserAccount() {
	}

	/** A new, unverified, ACTIVE account. */
	public static UserAccount register(String emailNormalized, String emailOriginal, String displayName,
			String passwordHash, Instant now) {
		UserAccount user = new UserAccount();
		user.id = UUID.randomUUID();
		user.emailNormalized = emailNormalized;
		user.emailOriginal = emailOriginal;
		user.displayName = displayName;
		user.passwordHash = passwordHash;
		user.status = Status.ACTIVE;
		user.createdAt = now;
		user.updatedAt = now;
		return user;
	}

	public UUID getId() {
		return id;
	}

	public String getEmailNormalized() {
		return emailNormalized;
	}

	public String getEmailOriginal() {
		return emailOriginal;
	}

	public String getDisplayName() {
		return displayName;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public Status getStatus() {
		return status;
	}

	public Instant getEmailVerifiedAt() {
		return emailVerifiedAt;
	}

	public boolean isEmailVerified() {
		return emailVerifiedAt != null;
	}

	/** Marks the email verified. A second call keeps the first verification time. */
	public void markEmailVerified(Instant now) {
		if (emailVerifiedAt == null) {
			emailVerifiedAt = now;
			updatedAt = now;
		}
	}

}
