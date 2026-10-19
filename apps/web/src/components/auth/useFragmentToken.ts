"use client";

import { useEffect, useRef, useState } from "react";

export type FragmentToken =
  | { status: "pending" }
  | { status: "missing" }
  /** `attempt` increases each time a link is opened, even the same link again. */
  | { status: "present"; token: string; attempt: number };

/**
 * Reads `#token=...` once and removes it from the address bar and history entry, so the secret does not stay in
 * the URL, the page's referrer, or a copied link. Fragments are never sent to a server. The value lives only in
 * memory for the life of the page.
 */
export function useFragmentToken(): FragmentToken {
  const captured = useRef<string | null | undefined>(undefined);
  const attempts = useRef(0);
  const [state, setState] = useState<FragmentToken>({ status: "pending" });

  useEffect(() => {
    const read = (): string | null => {
      const token = new URLSearchParams(window.location.hash.replace(/^#/, "")).get("token");
      if (window.location.hash) {
        window.history.replaceState(null, "", window.location.pathname + window.location.search);
      }
      return token;
    };
    // Strict Mode runs effects twice in development; the second run must reuse the first read.
    if (captured.current === undefined) {
      captured.current = read();
    }
    const token = captured.current;
    // The fragment exists only in the browser, so the state can only be set after mount.
    setState(token ? { status: "present", token, attempt: ++attempts.current } : { status: "missing" });

    // The same link opened again in this tab changes only the hash and does not reload the page.
    const onHashChange = () => {
      const next = read();
      if (next) {
        captured.current = next;
        setState({ status: "present", token: next, attempt: ++attempts.current });
      }
    };
    window.addEventListener("hashchange", onHashChange);
    return () => window.removeEventListener("hashchange", onHashChange);
  }, []);

  return state;
}
