import type { ReactNode } from "react";

import { LogoMark } from "./ui";

/**
 * The two-panel layout of the sign-in and sign-up mockups: brand panel on the left, a 308 px form column on the
 * right. Below 1024 px the brand panel shrinks to a header bar and the form comes first.
 */
export function AuthShell({
  title,
  subtitle,
  children,
  footer,
}: {
  title: string;
  subtitle?: string;
  children: ReactNode;
  footer?: ReactNode;
}) {
  return (
    <div className="grid min-h-dvh lg:grid-cols-2">
      <aside className="brand-grid relative flex flex-col justify-between overflow-hidden bg-page px-[33px] py-[33px] max-lg:border-b max-lg:border-line lg:border-r lg:border-line">
        <div className="brand-glow pointer-events-none absolute inset-0" aria-hidden="true" />
        <div className="relative flex items-center gap-2.5">
          <LogoMark />
          <span className="font-serif text-base text-ink">Helm</span>
        </div>
        <p className="relative hidden max-w-[380px] font-serif text-2xl leading-[30px] text-quote lg:block">
          Leadership intelligence for teams that review how they lead.
        </p>
      </aside>
      <main className="flex items-center justify-center bg-panel px-6 py-12">
        <div className="w-full max-w-[308px]">
          <h1 className="font-serif text-[26px] leading-8 text-ink">{title}</h1>
          {subtitle ? <p className="mt-1.5 text-[11px] leading-4 text-muted">{subtitle}</p> : null}
          <div className="mt-6 flex flex-col gap-3.5">{children}</div>
          {footer ? (
            <p className="mt-6 text-center text-[11px] leading-4 text-muted">{footer}</p>
          ) : null}
        </div>
      </main>
    </div>
  );
}
