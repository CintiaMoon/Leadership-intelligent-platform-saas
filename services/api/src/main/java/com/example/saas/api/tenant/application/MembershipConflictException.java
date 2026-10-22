package com.example.saas.api.tenant.application;

/**
 * The change is not possible in the current state (for example suspending a suspended member), or it would touch the
 * Owner. Becomes 409 {@code CONFLICT} (D-133). Nothing was changed.
 */
public class MembershipConflictException extends RuntimeException {

	public MembershipConflictException() {
		super(null, null, false, false);
	}

}
