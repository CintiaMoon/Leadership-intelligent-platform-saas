package com.example.saas.api.invitation.application;

/**
 * The invitation operation is not possible in the current state: the address already belongs to a member, an invitation
 * is already pending, or the invitation is no longer pending. Becomes 409 {@code CONFLICT}. Nothing was changed.
 */
public class InvitationConflictException extends RuntimeException {

	public InvitationConflictException() {
		super(null, null, false, false);
	}

}
