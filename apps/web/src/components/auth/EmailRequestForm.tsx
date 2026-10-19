"use client";

import { useState, type FormEvent } from "react";

import { post } from "@/lib/api/client";
import { describeFailure, type FormError } from "@/lib/api/messages";

import { Field, Notice, SubmitButton } from "./ui";

/**
 * An email-only form that always ends in the same neutral confirmation, whether or not an account exists
 * (AC-AUTH-20, 30). Used for the forgotten-password request and for resending the verification email.
 */
export function EmailRequestForm({
  path,
  submitLabel,
  pendingLabel,
  doneMessage,
}: {
  path: "password-reset/request" | "verify-email/resend";
  submitLabel: string;
  pendingLabel: string;
  doneMessage: string;
}) {
  const [pending, setPending] = useState(false);
  const [done, setDone] = useState(false);
  const [error, setError] = useState<FormError | null>(null);

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending) {
      return;
    }
    const data = new FormData(event.currentTarget);
    setPending(true);
    setError(null);
    const result = await post(path, { email: String(data.get("email") ?? "") });
    setPending(false);
    if (result.ok) {
      setDone(true);
      return;
    }
    setError(describeFailure(result));
  }

  if (done) {
    return <Notice tone="success">{doneMessage}</Notice>;
  }
  return (
    <form onSubmit={onSubmit} className="flex flex-col gap-3.5" noValidate>
      {error?.form ? <Notice tone="error">{error.form}</Notice> : null}
      <Field
        label="Work email"
        name="email"
        type="email"
        autoComplete="email"
        maxLength={254}
        required
        error={error?.fields.email}
      />
      <SubmitButton pending={pending} pendingLabel={pendingLabel}>
        {submitLabel}
      </SubmitButton>
    </form>
  );
}
