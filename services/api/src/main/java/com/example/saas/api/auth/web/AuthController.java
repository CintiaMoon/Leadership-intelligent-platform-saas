package com.example.saas.api.auth.web;

import com.example.saas.api.auth.application.ResendVerificationService;
import com.example.saas.api.auth.application.SignupService;
import com.example.saas.api.auth.application.VerifyEmailService;
import com.example.saas.api.security.ClientAddress;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
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

	private final SignupService signupService;

	private final VerifyEmailService verifyEmailService;

	private final ResendVerificationService resendVerificationService;

	AuthController(SignupService signupService, VerifyEmailService verifyEmailService,
			ResendVerificationService resendVerificationService) {
		this.signupService = signupService;
		this.verifyEmailService = verifyEmailService;
		this.resendVerificationService = resendVerificationService;
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

}
