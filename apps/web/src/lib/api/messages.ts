import type { ApiResult, FieldError } from "./client";

/**
 * Turns stable API codes into text. The API never sends display text for these, and the wording never says
 * whether an account exists (AC-AUTH-02, 13, 20, 30).
 */

const FIELD_MESSAGES: Record<string, string> = {
  REQUIRED: "This field is required.",
  INVALID: "This value is not valid.",
  INVALID_LENGTH: "This value is too long or too short.",
  INVALID_EMAIL: "Enter a valid email address.",
  TOO_SHORT: "Use at least 8 characters.",
  TOO_LONG: "Use at most 128 characters.",
  BREACHED_PASSWORD:
    "This password appears in known data breaches. Choose a different one.",
};

type Failure = Extract<ApiResult, { ok: false }>;

export type FormError = {
  /** A message for the whole form, or null when only fields are wrong. */
  form: string | null;
  fields: Record<string, string>;
};

export function describeFailure(
  failure: Failure,
  context: "login" | "token" | "invitation" | "default" = "default",
): FormError {
  const code = failure.problem?.code;
  const fields: Record<string, string> = {};
  for (const error of failure.problem?.errors ?? ([] as FieldError[])) {
    fields[error.field] = FIELD_MESSAGES[error.code] ?? FIELD_MESSAGES.INVALID;
  }
  if (code === "VALIDATION_FAILED" && Object.keys(fields).length > 0) {
    return { form: null, fields };
  }
  return { form: formMessage(failure, code, context), fields };
}

function formMessage(
  failure: Failure,
  code: string | undefined,
  context: "login" | "token" | "invitation" | "default",
): string {
  if (code === "INVALID_CREDENTIALS" && context === "login") {
    return "The email or password is incorrect, or the account can't sign in yet.";
  }
  if (context === "invitation" && code === "TOKEN_INVALID") {
    return "This invitation link is invalid or has expired. Ask the person who invited you to send a new one.";
  }
  if (context === "invitation" && code === "CONFLICT") {
    // The API answers both cases with the same code, so the text covers both (SPEC-INV Q-09).
    return "You can't join this company with this invitation. You may already be a member, or the company may have reached its member limit. Ask an administrator.";
  }
  if (code === "TOKEN_INVALID") {
    return "This link is invalid or has expired. Request a new one.";
  }
  if (code === "RATE_LIMITED" || failure.status === 429) {
    return waitMessage("Too many attempts.", failure.retryAfter);
  }
  if (code === "SERVICE_UNAVAILABLE" || failure.status === 503) {
    return waitMessage("The service is busy right now.", failure.retryAfter);
  }
  if (code === "CSRF_REJECTED") {
    return "Your request could not be verified. Reload the page and try again.";
  }
  return "Something went wrong. Please try again.";
}

function waitMessage(prefix: string, retryAfter: number | null): string {
  if (retryAfter === null) {
    return `${prefix} Please try again in a few minutes.`;
  }
  const minutes = Math.ceil(retryAfter / 60);
  return minutes <= 1
    ? `${prefix} Please try again in a minute.`
    : `${prefix} Please try again in about ${minutes} minutes.`;
}
