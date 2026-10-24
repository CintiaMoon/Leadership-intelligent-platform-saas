package com.example.saas.api.common;

import java.net.URI;
import java.util.List;

import com.example.saas.api.auth.application.InvalidCredentialsException;
import com.example.saas.api.auth.application.TokenInvalidException;
import com.example.saas.api.auth.domain.PasswordHashingBusyException;
import com.example.saas.api.ratelimit.RateLimitStoreUnavailableException;
import com.example.saas.api.invitation.application.InvitationConflictException;
import com.example.saas.api.security.ReauthenticationRequiredException;
import com.example.saas.api.tenant.application.CompanyNotFoundException;
import com.example.saas.api.tenant.application.DenialAudit;
import com.example.saas.api.tenant.application.MembershipConflictException;
import com.example.saas.api.tenant.application.PermissionDeniedException;
import com.example.saas.api.ratelimit.RateLimitedException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every MVC failure into an RFC 9457 problem with a stable code (ADR-005). Response text is fixed
 * per code; exception messages are never returned or logged because they can quote request content
 * (for example a malformed JSON body containing a password).
 */
@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	static final int STORE_RETRY_AFTER_SECONDS = 5;

	private final DenialAudit denialAudit;

	GlobalExceptionHandler(DenialAudit denialAudit) {
		this.denialAudit = denialAudit;
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<FieldProblem> errors = ex.getBindingResult().getFieldErrors().stream()
			.map(GlobalExceptionHandler::toFieldProblem)
			.toList();
		ProblemDetail problem = problem(ErrorCode.VALIDATION_FAILED, request);
		problem.setProperty("errors", errors);
		return ResponseEntity.badRequest().headers(headers).body(problem);
	}

	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		ErrorCode code = statusCode.value() == HttpStatus.BAD_REQUEST.value() ? ErrorCode.VALIDATION_FAILED
				: statusCode.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : ErrorCode.REQUEST_REJECTED;
		log.debug("Request rejected: {} -> {}", ex.getClass().getSimpleName(), statusCode.value());
		return ResponseEntity.status(statusCode).headers(headers).body(problem(code, request, statusCode));
	}

	@ExceptionHandler(RateLimitStoreUnavailableException.class)
	ResponseEntity<Object> handleStoreUnavailable(RateLimitStoreUnavailableException ex, HttpServletRequest request) {
		// Fail closed (ADR-004). The cause may mention connection details, so only the type is logged.
		log.warn("Rate-limit store unavailable: {}", ex.getCause() == null ? "unknown" : ex.getCause().getClass().getName());
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
			.header(HttpHeaders.RETRY_AFTER, String.valueOf(STORE_RETRY_AFTER_SECONDS))
			.body(problem(ErrorCode.SERVICE_UNAVAILABLE, request.getRequestURI()));
	}

	@ExceptionHandler(PessimisticLockingFailureException.class)
	ResponseEntity<Object> handleLockTimeout(PessimisticLockingFailureException ex, HttpServletRequest request) {
		// A tenant lock was not granted within the timeout (D-125): ask the client to retry. Nothing was changed.
		log.warn("Tenant lock not acquired: {}", ex.getClass().getSimpleName());
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
			.header(HttpHeaders.RETRY_AFTER, String.valueOf(STORE_RETRY_AFTER_SECONDS))
			.body(problem(ErrorCode.SERVICE_UNAVAILABLE, request.getRequestURI()));
	}

	@ExceptionHandler(CompanyNotFoundException.class)
	ResponseEntity<Object> handleCompanyNotFound(CompanyNotFoundException ex, HttpServletRequest request) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem(ErrorCode.NOT_FOUND, request.getRequestURI()));
	}

	@ExceptionHandler(MembershipConflictException.class)
	ResponseEntity<Object> handleMembershipConflict(MembershipConflictException ex, HttpServletRequest request) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(problem(ErrorCode.CONFLICT, request.getRequestURI()));
	}

	@ExceptionHandler(InvitationConflictException.class)
	ResponseEntity<Object> handleInvitationConflict(InvitationConflictException ex, HttpServletRequest request) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(problem(ErrorCode.CONFLICT, request.getRequestURI()));
	}

	@ExceptionHandler(ReauthenticationRequiredException.class)
	ResponseEntity<Object> handleReauthenticationRequired(ReauthenticationRequiredException ex,
			HttpServletRequest request) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN)
			.body(problem(ErrorCode.REAUTHENTICATION_REQUIRED, request.getRequestURI()));
	}

	@ExceptionHandler(PermissionDeniedException.class)
	ResponseEntity<Object> handlePermissionDenied(PermissionDeniedException ex, HttpServletRequest request) {
		// Written here, after the service's transaction has ended and returned its connection (D-125).
		ex.pendingAudit().ifPresent(pending -> {
			try {
				denialAudit.denied(pending.userId(), pending.companyId(), pending.permission());
			}
			catch (RuntimeException e) {
				log.error("Denial audit could not be written: {}", e.getClass().getName());
			}
		});
		return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem(ErrorCode.FORBIDDEN, request.getRequestURI()));
	}

	@ExceptionHandler(RateLimitedException.class)
	ResponseEntity<Object> handleRateLimited(RateLimitedException ex, HttpServletRequest request) {
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
			.header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()))
			.body(problem(ErrorCode.RATE_LIMITED, request.getRequestURI()));
	}

	@ExceptionHandler(PasswordHashingBusyException.class)
	ResponseEntity<Object> handleHashingBusy(PasswordHashingBusyException ex, HttpServletRequest request) {
		log.warn("Password hashing capacity exhausted");
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
			.header(HttpHeaders.RETRY_AFTER, String.valueOf(STORE_RETRY_AFTER_SECONDS))
			.body(problem(ErrorCode.SERVICE_UNAVAILABLE, request.getRequestURI()));
	}

	@ExceptionHandler(ValidationFailedException.class)
	ResponseEntity<Object> handleValidation(ValidationFailedException ex, HttpServletRequest request) {
		ProblemDetail problem = problem(ErrorCode.VALIDATION_FAILED, request.getRequestURI());
		problem.setProperty("errors", ex.errors());
		return ResponseEntity.badRequest().body(problem);
	}

	@ExceptionHandler(InvalidCredentialsException.class)
	ResponseEntity<Object> handleInvalidCredentials(InvalidCredentialsException ex, HttpServletRequest request) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
			.body(problem(ErrorCode.INVALID_CREDENTIALS, request.getRequestURI()));
	}

	@ExceptionHandler(TokenInvalidException.class)
	ResponseEntity<Object> handleTokenInvalid(TokenInvalidException ex, HttpServletRequest request) {
		return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
			.body(problem(ErrorCode.TOKEN_INVALID, request.getRequestURI()));
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<Object> handleUnexpected(Exception ex, HttpServletRequest request) {
		// Only the type: messages of persistence errors can quote row data such as emails and password hashes.
		log.error("Unhandled exception of type {}", ex.getClass().getName());
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
			.body(problem(ErrorCode.INTERNAL_ERROR, request.getRequestURI()));
	}

	private static FieldProblem toFieldProblem(FieldError error) {
		String constraint = error.getCode() == null ? "" : error.getCode();
		String code = switch (constraint) {
			case "NotNull", "NotBlank", "NotEmpty" -> "REQUIRED";
			case "Size", "Length", "Max", "Min" -> "INVALID_LENGTH";
			default -> "INVALID";
		};
		return new FieldProblem(error.getField(), code);
	}

	private static ProblemDetail problem(ErrorCode code, WebRequest request) {
		return problem(code, request, code.status());
	}

	private static ProblemDetail problem(ErrorCode code, WebRequest request, HttpStatusCode status) {
		String path = request instanceof ServletWebRequest servletRequest ? servletRequest.getRequest().getRequestURI()
				: "";
		ProblemDetail problem = problem(code, path);
		problem.setStatus(status.value());
		return problem;
	}

	private static ProblemDetail problem(ErrorCode code, String path) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), code.detail());
		problem.setType(URI.create(code.type()));
		problem.setTitle(code.status().getReasonPhrase());
		// Path only: the query string can carry data that must not be echoed.
		try {
			problem.setInstance(URI.create(path));
		}
		catch (IllegalArgumentException ex) {
			// Leave the instance unset rather than echo a malformed value.
		}
		problem.setProperty("code", code.name());
		return problem;
	}

}
