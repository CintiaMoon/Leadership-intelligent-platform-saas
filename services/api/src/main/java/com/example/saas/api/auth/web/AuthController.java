package com.example.saas.api.auth.web;

import com.example.saas.api.auth.application.AuthenticatedAccount;
import com.example.saas.api.auth.application.LoginRecorder;
import com.example.saas.api.auth.application.LoginService;
import com.example.saas.api.auth.application.PasswordResetRequestService;
import com.example.saas.api.auth.application.PasswordResetService;
import com.example.saas.api.auth.application.ResendVerificationService;
import com.example.saas.api.auth.application.SignupService;
import com.example.saas.api.auth.application.StepUpService;
import com.example.saas.api.auth.application.VerifyEmailService;
import com.example.saas.api.auth.persistence.UserAccount;
import com.example.saas.api.auth.persistence.UserAccountRepository;
import com.example.saas.api.security.AuthenticatedUser;
import com.example.saas.api.security.ClientAddress;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Thin controller: parses, delegates to the application services, and shapes the response. */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

	static final NeutralResponse SIGNUP_ACCEPTED = new NeutralResponse(
			"If this address can be registered, we have sent a verification email.");

	static final NeutralResponse RESEND_ACCEPTED = new NeutralResponse(
			"If an unverified account exists for this address, we have sent a new verification email.");

	static final NeutralResponse RESET_ACCEPTED = new NeutralResponse(
			"If an account exists for this address, we have sent password reset instructions.");

	private final PasswordResetRequestService passwordResetRequestService;

	private final PasswordResetService passwordResetService;

	private final SignupService signupService;

	private final VerifyEmailService verifyEmailService;

	private final ResendVerificationService resendVerificationService;

	private final LoginService loginService;

	private final StepUpService stepUpService;

	private final LoginRecorder recorder;

	private final SessionManager sessions;

	private final UserAccountRepository users;

	AuthController(SignupService signupService, VerifyEmailService verifyEmailService,
			ResendVerificationService resendVerificationService, LoginService loginService,
			StepUpService stepUpService, LoginRecorder recorder, SessionManager sessions,
			UserAccountRepository users, PasswordResetRequestService passwordResetRequestService,
			PasswordResetService passwordResetService) {
		this.passwordResetRequestService = passwordResetRequestService;
		this.passwordResetService = passwordResetService;
		this.signupService = signupService;
		this.verifyEmailService = verifyEmailService;
		this.resendVerificationService = resendVerificationService;
		this.loginService = loginService;
		this.stepUpService = stepUpService;
		this.recorder = recorder;
		this.sessions = sessions;
		this.users = users;
	}

	@PostMapping("/signup")
	ResponseEntity<NeutralResponse> signup(@Valid @RequestBody SignupRequest request, HttpServletRequest http) {
		signupService.signup(request.email(), request.password(), request.displayName(),
				ClientAddress.of(http).limitKey());
		return ResponseEntity.accepted().body(SIGNUP_ACCEPTED);
	}

	@PostMapping("/verify-email")
	ResponseEntity<Void> verifyEmail(@Valid @RequestBody TokenRequest request) {
		verifyEmailService.verify(request.token());
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/verify-email/resend")
	ResponseEntity<NeutralResponse> resend(@Valid @RequestBody EmailRequest request, HttpServletRequest http) {
		resendVerificationService.resend(request.email(), ClientAddress.of(http).limitKey());
		return ResponseEntity.accepted().body(RESEND_ACCEPTED);
	}

	@PostMapping("/password-reset/request")
	ResponseEntity<NeutralResponse> requestPasswordReset(@Valid @RequestBody EmailRequest request,
			HttpServletRequest http) {
		passwordResetRequestService.request(request.email(), ClientAddress.of(http).limitKey());
		return ResponseEntity.accepted().body(RESET_ACCEPTED);
	}

	@PostMapping("/password-reset/confirm")
	ResponseEntity<Void> confirmPasswordReset(@Valid @RequestBody PasswordResetConfirmRequest request) {
		passwordResetService.confirm(request.token(), request.newPassword());
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/login")
	ResponseEntity<SessionInfoResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
		AuthenticatedAccount account = loginService.login(request.email(), request.password(),
				ClientAddress.of(http).limitKey());
		return ResponseEntity.ok(sessions.establish(http, account));
	}

	@PostMapping("/logout")
	ResponseEntity<Void> logout(HttpServletRequest http) {
		sessions.end(http).ifPresent(recorder::sessionRevoked);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/session")
	ResponseEntity<SessionInfoResponse> session(@AuthenticationPrincipal AuthenticatedUser principal) {
		UserAccount user = users.findById(principal.userId()).orElseThrow();
		return ResponseEntity.ok(new SessionInfoResponse(user.getId(), user.getEmailOriginal(), user.getDisplayName(),
				user.isEmailVerified(), principal.lastAuthenticatedAt(), principal.sessionExpiresAt()));
	}

	@PostMapping("/step-up")
	ResponseEntity<Void> stepUp(@Valid @RequestBody StepUpRequest request, HttpServletRequest http,
			@AuthenticationPrincipal AuthenticatedUser principal) {
		stepUpService.confirmPassword(principal.userId(), request.password(), ClientAddress.of(http).limitKey());
		sessions.reauthenticated(http);
		return ResponseEntity.noContent().build();
	}

}
