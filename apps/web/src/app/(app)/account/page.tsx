import type { Metadata } from "next";

import type { SessionInfo } from "@/lib/server/bff";
import { requireSession } from "@/lib/server/session";

export const metadata: Metadata = { title: "Profile" };

export default async function AccountPage() {
  // The shell shows the header; the profile reads the session itself.
  const { session } = await requireSession();
  return <ProfileView session={session} />;
}

const dateFormat = new Intl.DateTimeFormat("en", {
  dateStyle: "medium",
  timeStyle: "short",
  timeZone: "UTC",
});

function when(iso: string): string {
  return `${dateFormat.format(new Date(iso))} UTC`;
}

function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  const letters = parts.length > 1 ? [parts[0], parts[parts.length - 1]] : parts.slice(0, 1);
  return letters.map((part) => Array.from(part)[0]?.toUpperCase() ?? "").join("");
}

function ProfileView({ session }: { session: SessionInfo }) {
  return (
    <>
      <div className="flex flex-wrap items-end justify-between gap-4 border-b border-line pb-4">
        <div>
          <p className="text-[9px] font-medium uppercase tracking-[0.22em] text-muted">Account</p>
          <h1 className="mt-1 font-serif text-[32px] leading-9 text-ink">Profile</h1>
          <p className="mt-1.5 text-[11px] text-muted">Your identity and sign-in status.</p>
        </div>
      </div>

      <div className="mt-5 grid gap-4 lg:grid-cols-[348px_1fr]">
        <section
          aria-label="Identity"
          className="flex flex-col items-center rounded-xl border border-line bg-surface p-5"
        >
          <div
            aria-hidden="true"
            className="flex size-16 items-center justify-center rounded-full bg-ink text-lg font-semibold text-page"
          >
            {initials(session.displayName)}
          </div>
          <h2 className="mt-4 text-center font-serif text-base text-ink">
            {session.displayName}
          </h2>
          {session.emailVerified ? (
            <span className="mt-2 inline-flex items-center gap-1 rounded-md border border-line px-2 py-0.5 text-[10px] text-ink">
              <svg aria-hidden="true" viewBox="0 0 24 24" className="size-3 text-accent" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <path d="M12 3 4 6v6c0 5 3.4 8 8 9 4.6-1 8-4 8-9V6l-8-3Z" />
              </svg>
              Email verified
            </span>
          ) : null}
          <p className="mt-4 [overflow-wrap:anywhere] text-center text-[11px] text-muted">{session.email}</p>
        </section>

        <div className="flex flex-col gap-4">
          <section aria-labelledby="personal" className="rounded-xl border border-line bg-surface p-5">
            <h2 id="personal" className="font-serif text-base text-ink">
              Personal information
            </h2>
            <dl className="mt-5 grid gap-4 sm:grid-cols-2">
              <Detail label="Full name" value={session.displayName} />
              <Detail label="Work email" value={session.email} />
            </dl>
          </section>
          <section aria-labelledby="signin" className="rounded-xl border border-line bg-surface p-5">
            <h2 id="signin" className="font-serif text-base text-ink">
              Sign-in
            </h2>
            <dl className="mt-5 grid gap-4 sm:grid-cols-2">
              <Detail label="Last signed in" value={when(session.lastAuthenticatedAt)} />
              <Detail label="This session ends" value={when(session.expiresAt)} />
            </dl>
          </section>
        </div>
      </div>
    </>
  );
}

function Detail({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex flex-col gap-[5px]">
      <dt className="text-[11px] font-semibold leading-4 text-ink">{label}</dt>
      <dd className="min-h-[29px] [overflow-wrap:anywhere] rounded-md border border-line bg-panel px-2.5 py-[7px] text-xs leading-[14px] text-ink">
        {value}
      </dd>
    </div>
  );
}
