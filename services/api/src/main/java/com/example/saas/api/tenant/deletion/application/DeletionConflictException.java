package com.example.saas.api.tenant.deletion.application;

/**
 * The deletion operation is not possible in the company's current state: it is not ACTIVE (a request), it is not pending
 * or its deadline has passed (a cancellation), or it is flagged for ownership resolution (a request, D-54). Becomes 409
 * {@code CONFLICT}. Nothing was changed.
 */
public class DeletionConflictException extends RuntimeException {

	public DeletionConflictException() {
		super(null, null, false, false);
	}

}
