"use client";

import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";

import { Field, Notice, SubmitButton } from "@/components/auth/ui";
import { send, type CompanySummary } from "@/lib/api/client";
import { describeFailure, type FormError } from "@/lib/api/messages";

/** Creates a company (SPEC-TEN): a name only. The role is decided by the server. */
export function CreateCompanyForm() {
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
    const result = await send("POST", "/api/companies", { name: String(data.get("name") ?? "") });
    if (result.ok) {
      const company = result.data as Partial<CompanySummary> | null;
      router.push(company?.id ? `/companies/${company.id}` : "/companies");
      router.refresh();
      return;
    }
    if (result.status === 401) {
      router.replace("/login?notice=expired");
      return;
    }
    setError(describeFailure(result, "company"));
    setPending(false);
  }

  return (
    <form onSubmit={onSubmit} className="flex flex-col gap-3.5" noValidate>
      {error?.form ? <Notice tone="error">{error.form}</Notice> : null}
      <Field
        label="Company name"
        name="name"
        autoComplete="organization"
        maxLength={100}
        required
        hint="Up to 100 characters. Names do not have to be unique."
        error={error?.fields.name}
      />
      <SubmitButton pending={pending} pendingLabel="Creating…">
        Create company
      </SubmitButton>
    </form>
  );
}
