import { loadConfig, upstreamHeaders, type BffConfig } from "./bff";

const UPSTREAM_TIMEOUT_MS = 10_000;

/** `/api/v1/` followed by segments of letters, digits and hyphens only: no dots, percent signs, queries or empty segments. */
const PLAIN_PATH = /^\/api\/v1\/[A-Za-z0-9-]+(\/[A-Za-z0-9-]+)*$/;

export type ReadResult<T> = { ok: true; data: T } | { ok: false; status: number };

/**
 * Reads one API resource for a server-rendered page, with the caller's session cookie (D-173). Only plain `/api/v1/` paths
 * are read; the browser never supplies the path, only ids that the page has already shown. A failure keeps its status
 * and nothing else: the page decides what to say, never the server's text. 0 means the API could not be reached.
 */
export async function readApi<T>(
  incoming: Headers,
  path: string,
  config: BffConfig = loadConfig(),
): Promise<ReadResult<T>> {
  if (!PLAIN_PATH.test(path)) {
    throw new Error("readApi takes a plain /api/v1/ path");
  }
  try {
    const response = await fetch(config.apiBaseUrl + path, {
      headers: upstreamHeaders(incoming, config, { json: false }),
      cache: "no-store",
      redirect: "manual",
      signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
    });
    if (!response.ok) {
      return { ok: false, status: response.status };
    }
    return { ok: true, data: (await response.json()) as T };
  } catch {
    return { ok: false, status: 0 };
  }
}
