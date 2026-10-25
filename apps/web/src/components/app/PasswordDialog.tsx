"use client";

import { useEffect, useId, useRef, useState, type FormEvent } from "react";

import { post } from "@/lib/api/client";
import { describeFailure } from "@/lib/api/messages";

import { PRIMARY_BUTTON, SECONDARY_BUTTON } from "./buttons";

/**
 * "Confirm your password" (P4, D-174): refreshes the login time for a sensitive action. The password lives only in
 * this field and is sent only to the step-up operation; it is cleared whenever the dialog closes. On success the caller
 * retries the action it started; on Cancel the action is dropped.
 */
export function PasswordDialog({
  open,
  onConfirmed,
  onCancel,
}: {
  open: boolean;
  onConfirmed: () => void;
  onCancel: () => void;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const formRef = useRef<HTMLFormElement>(null);
  const titleId = useId();
  const textId = useId();
  const fieldId = useId();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) {
      return;
    }
    if (open && !dialog.open) {
      if (typeof dialog.showModal === "function") {
        dialog.showModal();
      } else {
        dialog.setAttribute("open", "");
      }
    } else if (!open && dialog.open) {
      if (typeof dialog.close === "function") {
        dialog.close();
      } else {
        dialog.removeAttribute("open");
      }
    }
    if (!open) {
      formRef.current?.reset();
    }
  }, [open]);

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending) {
      return;
    }
    const password = String(new FormData(event.currentTarget).get("password") ?? "");
    setPending(true);
    setError(null);
    const result = await post("step-up", { password });
    setPending(false);
    if (result.ok) {
      formRef.current?.reset();
      onConfirmed();
      return;
    }
    setError(describeFailure(result, "login").form);
  }

  function cancel() {
    if (!pending) {
      setError(null);
      onCancel();
    }
  }

  return (
    <dialog
      ref={ref}
      aria-labelledby={titleId}
      aria-describedby={textId}
      onCancel={(event) => {
        event.preventDefault();
        cancel();
      }}
      className="m-auto w-[calc(100%-2rem)] max-w-[360px] rounded-xl border border-line bg-surface p-5 text-ink backdrop:bg-black/60"
    >
      <form ref={formRef} onSubmit={onSubmit} noValidate>
        <h2 id={titleId} className="font-serif text-base text-ink">
          Confirm your password
        </h2>
        <p id={textId} className="mt-2 text-xs leading-[18px] text-muted">
          For your security, enter your password to continue.
        </p>
        <label htmlFor={fieldId} className="mt-4 block text-[11px] font-semibold leading-4 text-ink">
          Password
        </label>
        <input
          id={fieldId}
          name="password"
          type="password"
          autoComplete="current-password"
          maxLength={128}
          required
          autoFocus
          aria-invalid={error ? true : undefined}
          className="mt-[5px] h-10 w-full rounded-md border border-line bg-panel px-2.5 text-base text-ink outline-none focus-visible:border-accent focus-visible:ring-2 focus-visible:ring-accent/60 sm:h-[29px] sm:text-xs"
        />
        {error ? (
          <p role="alert" className="mt-3 rounded-md border border-danger/50 px-3 py-2 text-xs text-danger">
            {error}
          </p>
        ) : null}
        <div className="mt-5 flex justify-end gap-2">
          <button type="button" className={SECONDARY_BUTTON} disabled={pending} onClick={cancel}>
            Cancel
          </button>
          <button type="submit" className={PRIMARY_BUTTON} disabled={pending}>
            {pending ? "Checking…" : "Continue"}
          </button>
        </div>
      </form>
    </dialog>
  );
}
