package com.example.saas.api.invitation.application;

import java.time.Instant;
import java.util.UUID;

import com.example.saas.api.invitation.domain.InvitationStatus;
import com.example.saas.api.tenant.domain.Role;

/**
 * An invitation as an Owner or Admin sees it. Never carries a token or its hash.
 *
 * @param emailSent false while the email has not been delivered yet (the token is minted at delivery, D-146)
 */
public record InvitationView(UUID id, String email, Role role, InvitationStatus status, Instant expiresAt,
		Instant createdAt, boolean emailSent) {
}
