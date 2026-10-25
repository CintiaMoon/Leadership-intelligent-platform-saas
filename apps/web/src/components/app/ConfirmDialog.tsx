"use client";

import { useEffect, useId, useRef, type ReactNode } from "react";

import { DANGER_BUTTON, PRIMARY_BUTTON, SECONDARY_BUTTON } from "./buttons";

/**
 * A confirmation in a native modal dialog: the browser traps focus, closes on Escape and returns focus to the control
 * that opened it. Cancel has the initial focus, so Enter does not confirm a destructive action by accident. While the
 * request is running both buttons are disabled; an error from it is shown inside the dialog.
 */
export function ConfirmDialog({
  open,
  title,
  children,
  confirmLabel,
  pendingLabel,
  pending,
  error,
  destructive = true,
  onConfirm,
  onCancel,
}: {
  open: boolean;
  title: string;
  children: ReactNode;
  confirmLabel: string;
  pendingLabel: string;
  pending: boolean;
  error: string | null;
  destructive?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  const textId = useId();

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) {
      return;
    }
    if (open && !dialog.open) {
      // jsdom has no showModal; real browsers do.
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
  }, [open]);

  return (
    <dialog
      ref={ref}
      aria-labelledby={titleId}
      aria-describedby={textId}
      onCancel={(event) => {
        // Escape: our state closes the dialog, and not while a request is running.
        event.preventDefault();
        if (!pending) {
          onCancel();
        }
      }}
      className="m-auto w-[calc(100%-2rem)] max-w-[360px] rounded-xl border border-line bg-surface p-5 text-ink backdrop:bg-black/60"
    >
      <h2 id={titleId} className="font-serif text-base text-ink">
        {title}
      </h2>
      <div id={textId} className="mt-2 text-xs leading-[18px] text-muted">
        {children}
      </div>
      {error ? (
        <p role="alert" className="mt-3 rounded-md border border-danger/50 px-3 py-2 text-xs text-danger">
          {error}
        </p>
      ) : null}
      <div className="mt-5 flex justify-end gap-2">
        <button type="button" autoFocus className={SECONDARY_BUTTON} disabled={pending} onClick={onCancel}>
          Cancel
        </button>
        <button
          type="button"
          className={destructive ? DANGER_BUTTON : PRIMARY_BUTTON}
          disabled={pending}
          onClick={onConfirm}
        >
          {pending ? pendingLabel : confirmLabel}
        </button>
      </div>
    </dialog>
  );
}
