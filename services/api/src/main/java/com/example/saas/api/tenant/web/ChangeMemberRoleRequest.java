package com.example.saas.api.tenant.web;

import com.example.saas.api.tenant.domain.Role;

/**
 * The new role. The contract allows ADMIN, AGENT and VIEWER only; OWNER and a missing value are refused by the rules
 * (400) so the check does not depend on this record. Unknown fields are rejected while parsing.
 */
record ChangeMemberRoleRequest(Role role) {
}
