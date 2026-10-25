import Link from "next/link";

import { LogoMark } from "@/components/auth/ui";
import { SignOutButton } from "@/components/auth/SignOutButton";

const LINK =
  "rounded-sm text-xs font-medium text-ink underline-offset-4 hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent";

/** The signed-in header (D-169): product name, My companies, the person's name (their profile) and Sign out. */
export function AppHeader({ displayName }: { displayName: string }) {
  return (
    <header className="border-b border-line">
      <div className="mx-auto flex max-w-[1070px] flex-wrap items-center justify-between gap-x-4 gap-y-2 px-4 py-2 sm:px-8">
        <div className="flex items-center gap-5">
          <Link
            href="/companies"
            className="flex items-center gap-2.5 rounded-sm focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
          >
            <LogoMark />
            <span className="font-serif text-base leading-none text-ink">Helm</span>
          </Link>
          <nav aria-label="Main">
            <Link href="/companies" className={LINK}>
              My companies
            </Link>
          </nav>
        </div>
        <div className="flex items-center gap-3">
          <Link href="/account" className={`${LINK} max-w-[40vw] truncate`} title="Your profile">
            {displayName}
          </Link>
          <SignOutButton />
        </div>
      </div>
    </header>
  );
}
