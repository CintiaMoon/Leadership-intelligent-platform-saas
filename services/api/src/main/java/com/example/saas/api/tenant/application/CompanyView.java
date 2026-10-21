package com.example.saas.api.tenant.application;

import java.time.Instant;
import java.util.UUID;

import com.example.saas.api.tenant.domain.CompanyStatus;
import com.example.saas.api.tenant.domain.Role;

/** A company as one caller sees it: the caller's role comes from their membership, never from a request. */
public record CompanyView(UUID id, String name, CompanyStatus status, Role role, Instant createdAt) {
}
