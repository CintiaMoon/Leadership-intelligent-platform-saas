package com.example.saas.api.tenant.application;

/**
 * The company already has as many ACTIVE members as its plan allows (AC-PLAN-05, Q-11): 409 {@code MEMBER_LIMIT_REACHED}.
 */
public class MemberLimitReachedException extends RuntimeException {

	public MemberLimitReachedException() {
		super(null, null, false, false);
	}

}
