/** Class names for buttons and links styled as buttons in the signed-in pages (same scale as the auth form controls). */
const BASE =
  "inline-flex h-10 items-center justify-center gap-2 rounded-md px-3 text-base font-medium focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent disabled:cursor-wait disabled:opacity-60 sm:h-[29px] sm:text-xs";

export const PRIMARY_BUTTON = `${BASE} bg-ink text-page transition-opacity hover:opacity-90`;
export const SECONDARY_BUTTON = `${BASE} border border-line bg-panel text-ink hover:bg-surface`;
export const DANGER_BUTTON = `${BASE} border border-danger/60 bg-panel text-danger hover:bg-surface`;
