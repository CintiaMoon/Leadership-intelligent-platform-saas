package com.example.saas.api.tenant.domain;

/** The ways a membership changes through the API (SPEC-MEM). Ownership transfer is a separate workflow (slice 005). */
public enum MembershipOperation {

	CHANGE_ROLE, SUSPEND, REACTIVATE, REMOVE, LEAVE

}
