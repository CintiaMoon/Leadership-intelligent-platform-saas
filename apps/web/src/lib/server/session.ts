import { cookies, headers } from "next/headers";
import { redirect } from "next/navigation";
import { cache } from "react";

import { loadConfig, loadSession, type BffConfig, type SessionInfo } from "./bff";

export const COMPANY_ID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

/**
 * The signed-in user for a server-rendered page, or a redirect to sign in. The layout and the page both call it;
 * `cache` makes that one lookup per request. The session is checked again by the API on every read and change.
 */
export const requireSession = cache(
  async (): Promise<{ session: SessionInfo; incoming: Headers; config: BffConfig }> => {
    const incoming = await headers();
    const config = loadConfig();
    const hadCookie = (await cookies()).has(config.sessionCookie);
    const session = await loadSession(incoming, config);
    if (!session) {
      redirect(hadCookie ? "/login?notice=expired" : "/login");
    }
    return { session, incoming, config };
  },
);

/** A read that came back 401 means the session ended since the page started: sign in again. */
export function signInAgain(): never {
  redirect("/login?notice=expired");
}
