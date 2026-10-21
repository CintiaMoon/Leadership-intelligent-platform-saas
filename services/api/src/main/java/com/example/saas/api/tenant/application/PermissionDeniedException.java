package com.example.saas.api.tenant.application;

/** An ACTIVE member whose role does not hold the permission (D-108). */
public class PermissionDeniedException extends RuntimeException {

	public PermissionDeniedException() {
		super(null, null, false, false);
	}

}
