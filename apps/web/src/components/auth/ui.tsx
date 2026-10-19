"use client";

import Link from "next/link";
import { useEffect, useId, useRef, type ComponentProps, type ReactNode } from "react";

/** Small form primitives sized to the Helm mockups (11 px labels, 12 px controls, 29 px control height). */

export function LogoMark({ className = "size-[26px]" }: { className?: string }) {
  return (
    <span
      aria-hidden="true"
      className={`inline-flex items-center justify-center rounded-md bg-accent text-page ${className}`}
    >
      <svg viewBox="0 0 24 24" className="size-[60%]" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M12 3v4M12 17v4M3 12h4M17 12h4M6 6l2.5 2.5M15.5 15.5 18 18M18 6l-2.5 2.5M8.5 15.5 6 18" />
      </svg>
    </span>
  );
}

export function ArrowRight() {
  return (
    <svg aria-hidden="true" viewBox="0 0 24 24" className="size-3.5" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M5 12h14M13 6l6 6-6 6" />
    </svg>
  );
}

type FieldProps = Omit<ComponentProps<"input">, "id"> & {
  label: string;
  error?: string;
  hint?: string;
  /** A control on the label row, such as "Forgot password?". */
  labelAction?: ReactNode;
};

export function Field({ label, error, hint, labelAction, className = "", ...input }: FieldProps) {
  const id = useId();
  const hintId = `${id}-hint`;
  const errorId = `${id}-error`;
  const describedBy = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(" ");
  return (
    <div className="flex flex-col gap-[5px]">
      <div className="flex items-baseline justify-between gap-2">
        <label htmlFor={id} className="text-[11px] font-semibold leading-4 text-ink">
          {label}
        </label>
        {labelAction}
      </div>
      <input
        id={id}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy || undefined}
        className={`h-10 w-full rounded-md border bg-panel px-2.5 text-base text-ink outline-none transition-colors placeholder:text-muted focus-visible:border-accent focus-visible:ring-2 focus-visible:ring-accent/60 sm:h-[29px] sm:text-xs ${
          error ? "border-danger" : "border-line"
        } ${className}`}
        {...input}
      />
      {hint && !error ? (
        <p id={hintId} className="text-[11px] leading-4 text-muted">
          {hint}
        </p>
      ) : null}
      {error ? (
        <p id={errorId} className="text-[11px] leading-4 text-danger">
          {error}
        </p>
      ) : null}
    </div>
  );
}

export function SubmitButton({
  pending,
  pendingLabel,
  children,
}: {
  pending: boolean;
  pendingLabel: string;
  children: ReactNode;
}) {
  return (
    <button
      type="submit"
      disabled={pending}
      className="inline-flex h-10 w-full items-center justify-center gap-2 rounded-md bg-ink text-base font-medium text-page transition-opacity hover:opacity-90 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent disabled:cursor-wait disabled:opacity-60 sm:h-[29px] sm:text-xs"
    >
      {pending ? pendingLabel : children}
      {pending ? null : <ArrowRight />}
    </button>
  );
}

/** A status or error message. Errors take focus so keyboard and screen-reader users land on them. */
export function Notice({
  tone,
  children,
}: {
  tone: "error" | "info" | "success";
  children: ReactNode;
}) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (tone === "error") {
      ref.current?.focus();
    }
  }, [tone, children]);
  const color =
    tone === "error"
      ? "border-danger/50 text-danger"
      : tone === "success"
        ? "border-accent/50 text-ink"
        : "border-line text-muted";
  return (
    <div
      ref={ref}
      tabIndex={-1}
      role={tone === "error" ? "alert" : "status"}
      className={`rounded-md border bg-surface px-3 py-2 text-xs leading-[18px] outline-none ${color}`}
    >
      {children}
    </div>
  );
}

export function TextLink({ className = "", ...props }: ComponentProps<typeof Link>) {
  return (
    <Link
      className={`rounded-sm font-medium text-ink underline-offset-2 hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent ${className}`}
      {...props}
    />
  );
}
