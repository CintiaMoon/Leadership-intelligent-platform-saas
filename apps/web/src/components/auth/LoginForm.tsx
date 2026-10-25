"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { useState, type FormEvent } from "react";

import { post } from "@/lib/api/client";
import { describeFailure, type FormError } from "@/lib/api/messages";
import { destinationAfterSignIn } from "@/components/invitations/invitationToken";

import { Field, Notice, SubmitButton, TextLink } from "./ui";

/** Messages shown after a redirect. Only these fixed keys are honoured; query text is never echoed. */
const NOTICES: Record<string, { tone: "info" | "success"; text: string }> = {
  expired: { tone: "info", text: "Your session has ended. Sign in again to continue." },
  verified: { tone: "success", text: "Your email is verified. You can sign in now." },
  reset: { tone: "success", text: "Your password was changed. Sign in with the new one." },
};

export function LoginForm() {
  const router = useRouter();
  const params = useSearchParams();
  const notice = NOTICES[params.get("notice") ?? ""];
  const next = params.get("next");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<FormError | null>(null);

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending) {
      return;
    }
    const data = new FormData(event.currentTarget);
    setPending(true);
    setError(null);
    const result = await post("login", {
      email: String(data.get("email") ?? ""),
      password: String(data.get("password") ?? ""),
    });
    if (result.ok) {
      router.replace(destinationAfterSignIn(next));
      router.refresh();
      return;
    }
    setError(describeFailure(result, "login"));
    setPending(false);
  }

  return (
    <form onSubmit={onSubmit} className="flex flex-col gap-3.5" noValidate>
      {error?.form ? (
        <Notice tone="error">{error.form}</Notice>
      ) : notice ? (
        <Notice tone={notice.tone}>{notice.text}</Notice>
      ) : null}
      <Field
        label="Work email"
        name="email"
        type="email"
        autoComplete="username"
        maxLength={254}
        required
        error={error?.fields.email}
      />
      <Field
        label="Password"
        name="password"
        type="password"
        autoComplete="current-password"
        maxLength={128}
        required
        error={error?.fields.password}
        labelAction={
          <TextLink href="/forgot-password" className="text-[11px] font-normal text-muted">
            Forgot password?
          </TextLink>
        }
      />
      <SubmitButton pending={pending} pendingLabel="Signing in…">
        Sign in
      </SubmitButton>
    </form>
  );
}
