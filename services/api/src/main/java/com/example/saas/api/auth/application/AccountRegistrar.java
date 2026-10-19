package com.example.saas.api.auth.application;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import com.example.saas.api.auth.outbox.AuthOutboxHandlers;
import com.example.saas.api.auth.outbox.OutboxRepository;
import com.example.saas.api.auth.persistence.AuditLog;
import com.example.saas.api.auth.persistence.UserAccount;
import com.example.saas.api.auth.persistence.UserAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional part of signup. The account, its outbox record, and its audit event commit together;
 * no email is sent here (CLAUDE.md principle 10). A concurrent signup for the same address loses on the
 * unique constraint, which surfaces as a {@code DataIntegrityViolationException} for the caller to handle.
 */
@Service
class AccountRegistrar {

	/** Result of {@link #register}. */
	enum Outcome {

		CREATED, ALREADY_EXISTS

	}

	private final UserAccountRepository users;

	private final OutboxRepository outbox;

	private final AuditLog audit;

	private final Clock clock;

	AccountRegistrar(UserAccountRepository users, OutboxRepository outbox, AuditLog audit, Clock clock) {
		this.users = users;
		this.outbox = outbox;
		this.audit = audit;
		this.clock = clock;
	}

	@Transactional
	Outcome register(String emailNormalized, String emailOriginal, String displayName, String passwordHash) {
		var existing = users.findByEmailNormalized(emailNormalized);
		if (existing.isPresent()) {
			enqueueNotice(existing.get().getId());
			return Outcome.ALREADY_EXISTS;
		}
		UserAccount user = UserAccount
			.register(emailNormalized, emailOriginal, displayName, passwordHash, clock.instant());
		// Flush now so a duplicate address fails here, inside this call, and not at commit.
		users.saveAndFlush(user);
		outbox.enqueue(AuthOutboxHandlers.VERIFICATION_EMAIL, Map.of("userId", user.getId().toString()),
				"verify-" + UUID.randomUUID(), clock.instant());
		audit.success("auth.user.registered", AuditLog.Actor.user, user.getId(), "user", user.getId());
		return Outcome.CREATED;
	}

	/** Used after losing a registration race: the account exists now, so notify its owner instead. */
	@Transactional
	void notifyExisting(String emailNormalized) {
		users.findByEmailNormalized(emailNormalized).ifPresent(user -> enqueueNotice(user.getId()));
	}

	private void enqueueNotice(UUID userId) {
		outbox.enqueue(AuthOutboxHandlers.ACCOUNT_EXISTS_NOTICE, Map.of("userId", userId.toString()),
				"exists-" + UUID.randomUUID(), clock.instant());
	}

}
