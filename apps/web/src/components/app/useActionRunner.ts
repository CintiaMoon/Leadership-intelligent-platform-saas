"use client";

import { useRouter } from "next/navigation";
import { useEffect, useRef, useState, useTransition } from "react";

import type { ApiResult } from "@/lib/api/client";
import { describeFailure } from "@/lib/api/messages";

type Retry = { id: string; request: () => Promise<ApiResult>; success: string };

/** `done`: the dialog that started the change can close. `succeeded`: the change itself was made. */
export type RunOutcome = { done: boolean; error: string | null; succeeded: boolean };

/**
 * Runs one change from a list page and handles every answer the same way (D-174, D-175): success says what happened
 * and reads the list again; a stale login asks for the password and sends the same request once more; a conflict or a
 * refusal explains and reads the list again; waiting messages come from the stable codes; a 401 goes to sign in.
 *
 * One change runs at a time: a second call while one is running does nothing, so a double click sends one request. The
 * row stays marked busy until the list has been read again. Controls are marked `aria-disabled` rather than disabled
 * (see the pages), so keyboard focus is not thrown to the page by a disabled control; when a row disappears and focus
 * is lost, it moves to the message so a keyboard or screen-reader user lands on the result.
 *
 * @param items the rows (or the transfer) on screen, only to notice when they have been read again
 */
export function useActionRunner(subject: "member" | "invitation" | "transfer", items: unknown) {
  const router = useRouter();
  const [notice, setNotice] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [retry, setRetry] = useState<Retry | null>(null);
  const [refreshing, startRefresh] = useTransition();
  const running = useRef(false);
  const [lastId, setLastId] = useState<string | null>(null);
  const noticeRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!notice) {
      return;
    }
    const active = document.activeElement;
    if (!active || active === document.body) {
      noticeRef.current?.focus();
    }
  }, [notice, items]);

  // The ownership page shows one transfer, not a list.
  const unit = subject === "transfer" ? "page" : "list";

  function explain(result: Extract<ApiResult, { ok: false }>): { text: string; refresh: boolean } {
    const code = result.problem?.code;
    if (code === "REAUTHENTICATION_REQUIRED") {
      return { text: "Your password is needed again for this. Please confirm it and try once more.", refresh: false };
    }
    if (code === "CSRF_REJECTED") {
      return { text: describeFailure(result).form ?? "Your request could not be verified. Reload the page.", refresh: false };
    }
    if (result.status === 403 || result.status === 404) {
      return { text: `That is not available to you any more. The ${unit} was refreshed.`, refresh: true };
    }
    if (result.status === 409) {
      return { text: `This ${subject} changed${subject === "transfer" ? "" : " in the meantime"}. The ${unit} was refreshed.`, refresh: true };
    }
    return { text: describeFailure(result).form ?? "Something went wrong. Please try again.", refresh: false };
  }

  /** `done` is true when the dialog that started the change can close. */
  async function run(
    id: string,
    request: () => Promise<ApiResult>,
    success: string,
    again = false,
  ): Promise<RunOutcome> {
    if (running.current) {
      return { done: false, error: null, succeeded: false };
    }
    running.current = true;
    setLastId(id);
    setBusyId(id);
    setNotice(null);
    try {
      const result = await request();
      if (result.ok) {
        setNotice(success);
        startRefresh(() => router.refresh());
        return { done: true, error: null, succeeded: true };
      }
      if (result.status === 401) {
        router.replace("/login?notice=expired");
        return { done: true, error: null, succeeded: false };
      }
      if (result.status === 403 && result.problem?.code === "REAUTHENTICATION_REQUIRED" && !again) {
        setRetry({ id, request, success });
        return { done: true, error: null, succeeded: false };
      }
      const { text, refresh } = explain(result);
      if (refresh) {
        startRefresh(() => router.refresh());
      }
      setNotice(text);
      return { done: refresh, error: text, succeeded: false };
    } finally {
      running.current = false;
      setBusyId(null);
    }
  }

  async function reauthenticated() {
    const pending = retry;
    setRetry(null);
    if (pending) {
      await run(pending.id, pending.request, pending.success, true);
    }
  }

  return {
    notice,
    noticeRef,
    /** The row that is being changed or is waiting for the refreshed list, if any. */
    busy: busyId ?? (refreshing ? lastId : null),
    run,
    reauthOpen: retry !== null,
    reauthenticated,
    cancelReauth: () => setRetry(null),
  };
}
