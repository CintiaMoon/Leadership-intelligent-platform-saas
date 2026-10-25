/** Dates are formatted on the server in UTC, so the server and the browser print the same text (no hydration mismatch). */
const date = new Intl.DateTimeFormat("en", { dateStyle: "medium", timeZone: "UTC" });
const dateTime = new Intl.DateTimeFormat("en", { dateStyle: "medium", timeStyle: "short", timeZone: "UTC" });

export function formatDate(iso: string): string {
  return date.format(new Date(iso));
}

/** For moments where the hour matters, such as when an invitation stops working. */
export function formatDateTimeUtc(iso: string): string {
  return `${dateTime.format(new Date(iso))} UTC`;
}
