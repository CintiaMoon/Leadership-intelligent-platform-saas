package com.example.saas.api.auth.application;

import java.time.Clock;
import java.util.UUID;

import com.example.saas.api.auth.persistence.AuditLog;
import com.example.saas.api.auth.persistence.UserAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The database writes that follow a login decision: audit events and the password-hash upgrade. */
@Service
public class LoginRecorder {

	private final UserAccountRepository users;

	private final AuditLog audit;

	private final Clock clock;

	LoginRecorder(UserAccountRepository users, AuditLog audit, Clock clock) {
		this.users = users;
		this.audit = audit;
		this.clock = clock;
	}

	/** @param upgradedHash a hash made with the current parameters to store, or null to keep the existing one */
	@Transactional
	public void succeeded(UUID userId, String upgradedHash) {
		if (upgradedHash != null) {
			users.findById(userId).ifPresent(user -> user.changePasswordHash(upgradedHash, clock.instant()));
		}
		audit.success("auth.login.succeeded", AuditLog.Actor.user, userId, "user", userId);
	}

	/** Recorded only for accounts that exist (D-88): the rest is counted by the throttle, not written one by one. */
	@Transactional
	public void failed(UUID userId) {
		audit.record("auth.login.failed", AuditLog.Actor.user, userId, "user", userId, "denied", "invalid_credentials");
	}

	@Transactional
	public void stepUpSucceeded(UUID userId) {
		audit.success("auth.step_up.succeeded", AuditLog.Actor.user, userId, "user", userId);
	}

	@Transactional
	public void sessionRevoked(UUID userId) {
		audit.success("auth.session.revoked", AuditLog.Actor.user, userId, "user", userId);
	}

}
