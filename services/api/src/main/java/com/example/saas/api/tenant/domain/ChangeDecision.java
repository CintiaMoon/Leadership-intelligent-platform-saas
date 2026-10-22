package com.example.saas.api.tenant.domain;

/**
 * The verdict on one membership change.
 *
 * @param outcome what to do with the request
 * @param deniedPermission for {@link Outcome#FORBIDDEN}, the matrix row that refused it (audited); otherwise null
 * @param recentAuthenticationRequired for {@link Outcome#ALLOWED}, whether the session must have logged in or stepped
 *        up within the window (promoting or demoting an Admin, AC-AUTH-14)
 */
public record ChangeDecision(Outcome outcome, Permission deniedPermission, boolean recentAuthenticationRequired) {

	public enum Outcome {

		/** The change may proceed. */
		ALLOWED,

		/** The actor's role does not hold the permission (403). */
		FORBIDDEN,

		/** The change is not possible in the current state, or would touch the Owner (409). */
		CONFLICT,

		/** The request itself is malformed, for example it asks to assign Owner (400). */
		INVALID

	}

	static ChangeDecision allowed(boolean recentAuthenticationRequired) {
		return new ChangeDecision(Outcome.ALLOWED, null, recentAuthenticationRequired);
	}

	static ChangeDecision forbidden(Permission permission) {
		return new ChangeDecision(Outcome.FORBIDDEN, permission, false);
	}

	static ChangeDecision conflict() {
		return new ChangeDecision(Outcome.CONFLICT, null, false);
	}

	static ChangeDecision invalid() {
		return new ChangeDecision(Outcome.INVALID, null, false);
	}

}
