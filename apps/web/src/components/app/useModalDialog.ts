"use client";

import { useCallback, useEffect, useRef } from "react";

/**
 * Keeps a native modal `<dialog>` in step with the `open` flag of the component that owns it.
 *
 * The browser can close a modal by itself (for example a second Escape while the first was ignored because a request
 * was running). If the component still wants the dialog open, the dialog is opened again, so the screen and the state
 * never disagree and the next click works. jsdom has no `showModal`, so tests fall back to the `open` attribute.
 */
export function useModalDialog(open: boolean) {
  const ref = useRef<HTMLDialogElement>(null);
  const wanted = useRef(open);

  useEffect(() => {
    wanted.current = open;
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
  }, [open]);

  const onClose = useCallback(() => {
    const dialog = ref.current;
    if (dialog && wanted.current && !dialog.open && typeof dialog.showModal === "function") {
      dialog.showModal();
    }
  }, []);

  return { ref, onClose };
}
