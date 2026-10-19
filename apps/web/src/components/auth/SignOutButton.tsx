"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";

import { post } from "@/lib/api/client";

export function SignOutButton() {
  const router = useRouter();
  const [pending, setPending] = useState(false);

  async function signOut() {
    if (pending) {
      return;
    }
    setPending(true);
    // Whatever the result, the person leaves: a 401 only means the session had already ended.
    await post("logout");
    router.replace("/login");
    router.refresh();
  }

  return (
    <button
      type="button"
      onClick={signOut}
      disabled={pending}
      className="inline-flex h-10 items-center gap-2 rounded-md border border-line bg-transparent px-3 text-base font-semibold text-ink transition-colors hover:bg-surface focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent disabled:cursor-wait disabled:opacity-60 sm:h-[26px] sm:text-[11px]"
    >
      <svg aria-hidden="true" viewBox="0 0 24 24" className="size-3.5" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9" />
      </svg>
      {pending ? "Signing out…" : "Sign out"}
    </button>
  );
}
