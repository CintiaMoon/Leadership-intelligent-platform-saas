package com.example.saas.api.tenant.domain;

/** Whether a member can access the company at all (SPEC-TEN). Only ACTIVE memberships count as active members. */
public enum MembershipStatus {

	ACTIVE, SUSPENDED, REMOVED

}
