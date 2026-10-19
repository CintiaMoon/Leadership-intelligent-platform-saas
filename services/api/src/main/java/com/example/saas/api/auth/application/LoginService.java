package com.example.saas.api.auth.application;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import com.example.saas.api.auth.domain.EmailAddress;
import com.example.saas.api.auth.domain.PasswordHasher;
import com.example.saas.api.auth.domain.PasswordPolicy;
import com.example.saas.api.auth.persistence.UserAccount;
import com.example.saas.api.auth.persistence.UserAccountRepository;
import com.example.saas.api.common.FieldProblem;
import com.example.saas.api.common.ValidationFailedException;
import com.example.saas.api.ratelimit.LoginThrottle;
import com.example.saas.api.ratelimit.RateLimiter;
import org.springframework.stereotype.Service;

/**
 * Email and password login (AC-AUTH-05, 13, 18, 19, 25, 26, 27, 29; D-78, D-80).
 *
 * <p>Every failure looks the same to the caller, and every failure that reaches the password check does the
 * same expensive work: a real verification when the account exists and a verification against a dummy hash
 * when it does not, so timing does not tell them apart. Throttled attempts (too many from one address, or a
 * blocked account and address pair) are refused before the password is evaluated, with the same response.
 */
@Service
public class LoginService {

	private final RateLimiter rateLimiter;

	private final LoginThrottle throttle;

	private final UserAccountRepository users;

	private final PasswordHasher hasher;

	private final LoginRecorder recorder;

	private final ReentrantLock[] gates = Stream.generate(ReentrantLock::new).limit(256).toArray(ReentrantLock[]::new);

	LoginService(RateLimiter rateLimiter, LoginThrottle throttle, UserAccountRepository users,
			PasswordHasher hasher, LoginRecorder recorder) {
		this.rateLimiter = rateLimiter;
		this.throttle = throttle;
		this.users = users;
		this.hasher = hasher;
		this.recorder = recorder;
	}

	/**
	 * @return the authenticated account; the caller opens the session
	 * @throws ValidationFailedException the email or password field is missing or malformed (400)
	 * @throws InvalidCredentialsException the login failed for any reason (401)
	 * @throws com.example.saas.api.auth.domain.PasswordHashingBusyException no hashing capacity (503)
	 * @throws com.example.saas.api.ratelimit.RateLimitStoreUnavailableException counters unavailable: fail closed (503)
	 */
	public AuthenticatedAccount login(String email, String password, String clientKey) {
		EmailAddress address = EmailAddress.parse(email)
			.orElseThrow(() -> new ValidationFailedException(List.of(new FieldProblem("email", "INVALID_EMAIL"))));
		if (password == null || password.isEmpty()) {
			throw new ValidationFailedException(List.of(new FieldProblem("password", "REQUIRED")));
		}
		String normalized = address.normalized();

		boolean withinIpLimit = rateLimiter.hit(AuthRateLimits.LOGIN_IP, clientKey).allowed();
		if (!withinIpLimit) {
			throw new InvalidCredentialsException();
		}

		// Attempts for one account from one address run one at a time on this instance, so a burst cannot all pass the
		// block check before the first failure is counted (AC-AUTH-18). Other accounts and addresses are unaffected.
		ReentrantLock gate = gateFor(normalized, clientKey);
		gate.lock();
		try {
			return evaluate(normalized, PasswordPolicy.normalize(password), clientKey);
		}
		finally {
			gate.unlock();
		}
	}

	private ReentrantLock gateFor(String normalizedEmail, String clientKey) {
		return gates[Math.floorMod((normalizedEmail + "|" + clientKey).hashCode(), gates.length)];
	}

	private AuthenticatedAccount evaluate(String normalized, String candidate, String clientKey) {
		if (throttle.isBlocked(normalized, clientKey)) {
			throw new InvalidCredentialsException();
		}

		Optional<UserAccount> found = users.findByEmailNormalized(normalized);
		boolean passwordMatches;
		if (found.isPresent()) {
			passwordMatches = hasher.matches(candidate, found.get().getPasswordHash());
		}
		else {
			hasher.verifyDummy(candidate);
			passwordMatches = false;
		}
		boolean eligible = found.map(user -> user.getStatus() == UserAccount.Status.ACTIVE && user.isEmailVerified())
			.orElse(false);

		if (!passwordMatches || !eligible) {
			throttle.recordFailure(normalized, clientKey);
			found.ifPresent(user -> recorder.failed(user.getId()));
			throw new InvalidCredentialsException();
		}

		UserAccount user = found.get();
		throttle.recordSuccess(normalized, clientKey);
		String upgradedHash = hasher.needsUpgrade(user.getPasswordHash()) ? hasher.hash(candidate) : null;
		recorder.succeeded(user.getId(), user.getPasswordHash(), upgradedHash);
		return new AuthenticatedAccount(user.getId(), user.getEmailOriginal(), user.getDisplayName(), true,
				user.getCredentialsVersion());
	}

}
