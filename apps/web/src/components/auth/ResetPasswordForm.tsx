"use client";

import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";

import { post } from "@/lib/api/client";
import { describeFailure, type FormError } from "@/lib/api/messages";

import { Field, Notice, SubmitButton, TextLink } from "./ui";
import { useFragmentToken } from "./useFragmentToken";

export function ResetPasswordForm() {
  const router = useRouter();
  const fragment = useFragmentToken();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<FormError | null>(null);
  const [tokenRejected, setTokenRejected] = useState(false);

  if (fragment.status === "pending") {
    return <p className="text-xs text-muted">Checking your link…</p>;
  }
  if (fragment.status === "missing" || tokenRejected) {
    return (
      <>
        <Notice tone="error">
          {tokenRejected
            ? "This link is invalid or has expired."
            : "This link is incomplete. Open the link from your email again."}
        </Notice>
        <TextLink href="/forgot-password" className="text-xs">
          Request a new reset link
        </TextLink>
      </>
    );
  }

  const token = fragment.token;

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending) {
      return;
    }
    const data = new FormData(event.currentTarget);
    setPending(true);
    setError(null);
    const result = await post("password-reset/confirm", {
      token,
      newPassword: String(data.get("newPassword") ?? ""),
    });
    if (result.ok) {
      router.replace("/login?notice=reset");
      return;
    }
    if (result.problem?.code === "TOKEN_INVALID") {
      setTokenRejected(true);
      return;
    }
    // A rejected password leaves the token unused, so the person can simply choose another.
    setError(describeFailure(result, "token"));
    setPending(false);
  }

  return (
    <form onSubmit={onSubmit} className="flex flex-col gap-3.5" noValidate>
      {error?.form ? <Notice tone="error">{error.form}</Notice> : null}
      <Field
        label="New password"
        name="newPassword"
        type="password"
        autoComplete="new-password"
        maxLength={128}
        required
        hint="8 to 128 characters. You will be signed out everywhere."
        error={error?.fields.newPassword}
      />
      <SubmitButton pending={pending} pendingLabel="Saving…">
        Set new password
      </SubmitButton>
    </form>
  );
}
