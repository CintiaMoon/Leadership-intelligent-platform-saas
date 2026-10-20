package com.example.saas.api.auth.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.example.saas.api.auth.domain.DisplayName;
import com.example.saas.api.auth.domain.EmailAddress;
import com.example.saas.api.auth.domain.PasswordHasher;
import com.example.saas.api.auth.domain.PasswordPolicy;
import com.example.saas.api.common.FieldProblem;
import com.example.saas.api.common.ValidationFailedException;
import com.example.saas.api.ratelimit.RateLimiter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Account registration (AC-AUTH-01, 02, 17, 38, 40; D-79). The caller always receives the same outcome
 * whether the address is new or already registered: the work that differs happens after the response,
 * through the outbox, and the password is hashed in both cases so the timing does not tell them apart.
 */
@Service
public class SignupService {

	private final RateLimiter rateLimiter;

	private final PasswordPolicy passwordPolicy;

	private final PasswordHasher passwordHasher;

	private final AccountRegistrar registrar;

	private final Clock clock;

	SignupService(RateLimiter rateLimiter, PasswordPolicy passwordPolicy, PasswordHasher passwordHasher,
			AccountRegistrar registrar, Clock clock) {
		this.rateLimiter = rateLimiter;
		this.passwordPolicy = passwordPolicy;
		this.passwordHasher = passwordHasher;
		this.registrar = registrar;
		this.clock = clock;
	}

	/**
	 * @param clientKey the rate-limit key of the client address
	 * @throws ValidationFailedException the input is invalid (400)
	 * @throws com.example.saas.api.ratelimit.RateLimitedException the client exceeded the registration limit (429)
	 * @throws com.example.saas.api.ratelimit.RateLimitStoreUnavailableException counters unavailable: fail closed (503)
	 */
	public void signup(String email, String password, String displayName, String clientKey) {
		AuthRateLimits.requireWithin(rateLimiter.hit(AuthRateLimits.REGISTRATION_IP, clientKey), clock);

		List<FieldProblem> errors = new ArrayList<>();
		Optional<EmailAddress> address = EmailAddress.parse(email);
		if (address.isEmpty()) {
			errors.add(new FieldProblem("email", "INVALID_EMAIL"));
		}
		Optional<String> name = DisplayName.parse(displayName);
		if (name.isEmpty()) {
			errors.add(new FieldProblem("displayName", "INVALID"));
		}
		passwordPolicy.check(password)
			.ifPresent(violation -> errors.add(new FieldProblem("password", violation.name())));
		if (!errors.isEmpty()) {
			throw new ValidationFailedException(errors);
		}

		String hash = passwordHasher.hash(PasswordPolicy.normalize(password));
		// Applied to new and existing addresses alike, so exceeding it reveals nothing about the address.
		boolean withinAccountLimit = rateLimiter.hit(AuthRateLimits.SIGNUP_ACCOUNT, address.get().normalized())
			.allowed();
		if (!withinAccountLimit) {
			return;
		}
		try {
			registrar.register(address.get().normalized(), address.get().original(), name.get(), hash);
		}
		catch (DataIntegrityViolationException lostRace) {
			// A concurrent signup created the account between our check and insert. It exists now, so register again:
			// an unverified account is taken over (D-97) and a verified one gets the notice.
			registerAgain(address.get(), name.get(), hash);
		}
		catch (OptimisticLockingFailureException concurrentTakeover) {
			// Another signup replaced the same unverified account at the same moment; theirs stands and its
			// verification email is on its way. The response stays the neutral one.
		}
	}

	private void registerAgain(EmailAddress address, String name, String hash) {
		try {
			registrar.register(address.normalized(), address.original(), name, hash);
		}
		catch (DataIntegrityViolationException | OptimisticLockingFailureException ignored) {
			// Lost again to a concurrent signup for the same address; nothing more to do.
		}
	}

}
