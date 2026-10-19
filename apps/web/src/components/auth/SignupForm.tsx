"use client";

import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";

import { post } from "@/lib/api/client";
import { describeFailure, type FormError } from "@/lib/api/messages";

import { Field, Notice, SubmitButton } from "./ui";

export function SignupForm() {
  const router = useRouter();
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
    const result = await post("signup", {
      displayName: String(data.get("displayName") ?? ""),
      email: String(data.get("email") ?? ""),
      password: String(data.get("password") ?? ""),
    });
    if (result.ok) {
      router.push("/check-email");
      return;
    }
    setError(describeFailure(result));
    setPending(false);
  }

  return (
    <form onSubmit={onSubmit} className="flex flex-col gap-3.5" noValidate>
      {error?.form ? <Notice tone="error">{error.form}</Notice> : null}
      <Field
        label="Full name"
        name="displayName"
        autoComplete="name"
        maxLength={100}
        required
        error={error?.fields.displayName}
      />
      <Field
        label="Work email"
        name="email"
        type="email"
        autoComplete="email"
        maxLength={254}
        required
        error={error?.fields.email}
      />
      <Field
        label="Password"
        name="password"
        type="password"
        autoComplete="new-password"
        maxLength={128}
        required
        hint="8 to 128 characters. Longer passphrases are fine."
        error={error?.fields.password}
      />
      <SubmitButton pending={pending} pendingLabel="Creating account…">
        Create account
      </SubmitButton>
    </form>
  );
}
