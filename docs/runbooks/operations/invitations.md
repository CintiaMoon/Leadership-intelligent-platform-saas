# Invitations: deployment and operating notes

Slice 004 (SPEC-INV). These are requirements on the environment around the API; the code cannot enforce them.

## Before enabling invitations in an environment
1. **Client address source (D-166).** Set `CLIENT_IP_SOURCE` explicitly for the web app. Behind CloudFront and a load balancer use `cloudfront`; the default `rightmost` takes the last `X-Forwarded-For` entry, which behind a CDN is the edge address and would put every user in one throttle bucket. The failed-accept throttle (10 per hour per address) and the other per-address limits depend on it.
2. **Do not log query strings of the accept route.** The token is only ever in the request body, but anything in front of the API that records request lines (load balancer, CDN, WAF) must not keep the query string of `/api/v1/invitations/accept`. The API itself logs no query at the levels used outside tests (`InvitationLogHygieneTests`); framework loggers must not be set to DEBUG in shared environments, because Spring Security's DEBUG line quotes the query.
3. **Email click and open tracking must be off** for the sending provider (for example SES configuration sets). Tracking rewrites the link, and the token in the address fragment would be lost or exposed to the tracking service.
4. **Set the public base URL** (`PUBLIC_BASE_URL`) to the real origin of the web app; the invitation link is built from it.

## Things that look like faults but are by design
- **A retried delivery replaces the link (Q-03).** The token is minted when the email is prepared. If the send succeeds but the outbox cannot mark it sent, the retry mints a new token and the first link stops working; the person has two emails and only the newer link works. A resend by an Owner or Admin fixes it. `emailSent: true` means a link was issued, not that the message arrived (D-167); check the outbox row for the delivery state.
- **A person who verifies their address in another tab or device** opens the invitation link from the email again. The link stays valid until it is used, revoked, replaced by a resend or past 7 days from the last send.
- **429 on accept for a whole office.** Ten failed attempts from one address in an hour block further attempts from that address until the hour ends (D-166). Successful acceptances do not count.
- **Recipient limit 429.** At most 3 sends per address per company and 10 across companies per 24 hours (D-164).

## Running the end-to-end suite on a developer machine
`apps/web/playwright.config.ts` passes the database settings through `DB_URL`, but a locally built API jar contains the git-ignored `application-local.yml`, which fixes the datasource to the development database and ignores `DB_URL`. Override it with `SPRING_APPLICATION_JSON` (datasource URL and password, BFF credential, signing keys, public base URL, allowed origin) so the run uses the E2E database on port 55432, never the development one. CI is not affected.
