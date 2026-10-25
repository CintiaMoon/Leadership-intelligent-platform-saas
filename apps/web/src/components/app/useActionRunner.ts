"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";

import type { ApiResult } from "@/lib/api/client";
import { describeFailure } from "@/lib/api/messages";

type Retry = { id: string; request: () => Promise<ApiResult>; success: string };

export type RunOutcome = { done: boolean; error: string | null };

/**
 * Runs one change from a list page and handles every answer the same way (D-174, D-175): success says what happened
 * and reads the list again; a stale login asks for the password and sends the same request once more; a conflict or a
 * refusal explains and reads the list again; waiting messages come from the stable codes; a 401 goes to sign in.
 */
export function useActionRunner(subject: "member" | "invitation") {
  const router = useRouter();
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [retry, setRetry] = useState<Retry | null>(null);

  function explain(result: Extract<ApiResult, { ok: false }>): { text: string; refresh: boolean } {
    if (result.status === 403 || result.status === 404) {
      return { text: "That is not available to you any more. The list was refreshed.", refresh: true };
    }
    if (result.status === 409) {
      return { text: `This ${subject} changed in the meantime. The list was refreshed.`, refresh: true };
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
    setBusy(id);
    setNotice(null);
    const result = await request();
    setBusy(null);
    if (result.ok) {
      setNotice(success);
      router.refresh();
      return { done: true, error: null };
    }
    if (result.status === 401) {
      router.replace("/login?notice=expired");
      return { done: true, error: null };
    }
    if (result.status === 403 && result.problem?.code === "REAUTHENTICATION_REQUIRED" && !again) {
      setRetry({ id, request, success });
      return { done: true, error: null };
    }
    const { text, refresh } = explain(result);
    if (refresh) {
      router.refresh();
    }
    setNotice(text);
    return { done: refresh, error: text };
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
    busy,
    run,
    reauthOpen: retry !== null,
    reauthenticated,
    cancelReauth: () => setRetry(null),
  };
}
