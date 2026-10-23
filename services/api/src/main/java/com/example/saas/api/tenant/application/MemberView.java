package com.example.saas.api.tenant.application;

import java.time.Instant;
import java.util.UUID;

import com.example.saas.api.tenant.domain.MembershipStatus;
import com.example.saas.api.tenant.domain.Role;

/** A member as an Owner or Admin sees them (D-132). The ID is the membership's, never the user's. */
public record MemberView(UUID id, String displayName, String email, Role role, MembershipStatus status,
		Instant joinedAt, boolean self) {
}
