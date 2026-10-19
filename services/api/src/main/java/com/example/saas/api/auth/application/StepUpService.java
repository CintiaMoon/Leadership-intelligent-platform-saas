package com.example.saas.api.auth.application;

import java.util.UUID;

import com.example.saas.api.auth.domain.PasswordHasher;
import com.example.saas.api.auth.domain.PasswordPolicy;
import com.example.saas.api.auth.persistence.UserAccount;
import com.example.saas.api.auth.persistence.UserAccountRepository;
import com.example.saas.api.ratelimit.LoginThrottle;
import com.example.saas.api.ratelimit.RateLimiter;
import org.springframework.stereotype.Service;

/**
 * Re-checks the password of a logged-in user so the session can record a fresh authentication time (AC-AUTH-14,
 * 22). A wrong password counts against the same throttle as login and gets the same response.
 */
@Service
public class StepUpService {

	private final RateLimiter rateLimiter;

	private final LoginThrottle throttle;

	private final UserAccountRepository users;

	private final PasswordHasher hasher;

	private final LoginRecorder recorder;

	StepUpService(RateLimiter rateLimiter, LoginThrottle throttle, UserAccountRepository users,
			PasswordHasher hasher, LoginRecorder recorder) {
		this.rateLimiter = rateLimiter;
		this.throttle = throttle;
		this.users = users;
		this.hasher = hasher;
		this.recorder = recorder;
	}

	/** @throws InvalidCredentialsException the password is wrong, the account no longer qualifies, or it is throttled */
	public void confirmPassword(UUID userId, String password, String clientKey) {
		UserAccount user = users.findById(userId)
			.filter(account -> account.getStatus() == UserAccount.Status.ACTIVE && account.isEmailVerified())
			.orElseThrow(InvalidCredentialsException::new);
		String normalized = user.getEmailNormalized();

		boolean withinIpLimit = rateLimiter.hit(AuthRateLimits.LOGIN_IP, clientKey).allowed();
		if (!withinIpLimit || throttle.isBlocked(normalized, clientKey)) {
			throw new InvalidCredentialsException();
		}
		if (!hasher.matches(PasswordPolicy.normalize(password), user.getPasswordHash())) {
			throttle.recordFailure(normalized, clientKey);
			recorder.failed(user.getId());
			throw new InvalidCredentialsException();
		}
		throttle.recordSuccess(normalized, clientKey);
		recorder.stepUpSucceeded(user.getId());
	}

}
