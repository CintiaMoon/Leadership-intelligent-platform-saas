package com.example.saas.api.invitation.application;

import java.time.Duration;

import com.example.saas.api.ratelimit.RateLimitPolicy;

/** Invitation limits (SPEC-INV, D-52, D-152). Creating and resending both count. */
public final class InvitationRateLimits {

	/** 20 invitations (and resends) per inviter per hour (AC-INV-23). */
	public static final RateLimitPolicy PER_INVITER = new RateLimitPolicy("invitation-inviter", 20, Duration.ofHours(1));

	/** 50 per company per day (AC-INV-23). */
	public static final RateLimitPolicy PER_COMPANY = new RateLimitPolicy("invitation-company", 50, Duration.ofDays(1));

	/** 3 sends per recipient address per 24 hours (AC-INV-23). The key is the normalized address, hashed by the store. */
	public static final RateLimitPolicy PER_RECIPIENT = new RateLimitPolicy("invitation-recipient", 3,
			Duration.ofHours(24));

	/** Failed attempts to accept with a token, per client address per hour (AC-INV-11); used by the accept path. */
	public static final RateLimitPolicy FAILED_ACCEPT_PER_IP = new RateLimitPolicy("invitation-accept-failure-ip", 10,
			Duration.ofHours(1));

	private InvitationRateLimits() {
	}

}
