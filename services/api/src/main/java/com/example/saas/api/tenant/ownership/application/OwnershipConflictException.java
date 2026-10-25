package com.example.saas.api.tenant.ownership.application;

/**
 * The ownership operation is not possible in the current state: a transfer is already pending, the transfer is not
 * pending any more or is overdue, the recipient is not eligible, or the initiator is no longer the Owner. Becomes 409
 * {@code CONFLICT}. Nothing was changed.
 */
public class OwnershipConflictException extends RuntimeException {

	public OwnershipConflictException() {
		super(null, null, false, false);
	}

}
