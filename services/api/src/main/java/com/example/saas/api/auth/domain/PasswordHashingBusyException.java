package com.example.saas.api.auth.domain;

/** No hashing capacity was free within the wait limit; the request is refused with 503 (AC-AUTH-28, 39). */
public class PasswordHashingBusyException extends RuntimeException {

	public PasswordHashingBusyException() {
		super("Password hashing capacity exhausted");
	}

}
