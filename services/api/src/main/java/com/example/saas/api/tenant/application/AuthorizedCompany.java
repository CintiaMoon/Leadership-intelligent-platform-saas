package com.example.saas.api.tenant.application;

import java.util.UUID;

import com.example.saas.api.tenant.domain.Role;

/**
 * Proof that the caller is an ACTIVE member of the company with a role that holds the permission, as of this request.
 * Only {@link CompanyAuthorizationService} creates it. Controllers receive it instead of reading the path variable.
 */
public record AuthorizedCompany(UUID companyId, UUID userId, Role role) {
}
