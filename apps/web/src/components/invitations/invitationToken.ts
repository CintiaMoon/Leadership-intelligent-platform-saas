/**
 * Where an invitation token waits while the person signs in or creates an account (SPEC-INV Q-14, Q-15). It is kept
 * in this tab's `sessionStorage` only: it is not sent anywhere until the accept request, it does not outlive the tab,
 * and it is removed as soon as the invitation has been used, refused or declined. Storage can be unavailable (private
 * windows, blocked site data), so every access is guarded and the page still works without it.
 */
const KEY = "helm.invitation-token";

/** The value of `?next=` that sends a person back to the accept page. A fixed key, never a path, so it cannot redirect elsewhere. */
export const INVITATION_NEXT = "invitation";

export const ACCEPT_PATH = "/invitations/accept";

export function saveInvitationToken(token: string): void {
  try {
    window.sessionStorage.setItem(KEY, token);
  } catch {
    // Without storage the person must open the link again after signing in.
  }
}

export function loadInvitationToken(): string | null {
  try {
    return window.sessionStorage.getItem(KEY);
  } catch {
    return null;
  }
}

export function clearInvitationToken(): void {
  try {
    window.sessionStorage.removeItem(KEY);
  } catch {
    // Nothing to remove.
  }
}

/** Where to go after signing in: back to the accept page when the person came from an invitation, otherwise My companies. */
export function destinationAfterSignIn(next: string | null): string {
  return next === INVITATION_NEXT ? ACCEPT_PATH : "/companies";
}
