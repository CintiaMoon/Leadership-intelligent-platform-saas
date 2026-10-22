package com.example.saas.api.tenant.persistence;

import java.time.Instant;
import java.util.UUID;

import com.example.saas.api.tenant.domain.MembershipStatus;
import com.example.saas.api.tenant.domain.Role;

/** One line of the member list: a membership joined with the person's name and address. Never leaves the service. */
public record MemberRow(UUID membershipId, UUID userId, String displayName, String email, Role role,
		MembershipStatus status, Instant joinedAt) {
}
