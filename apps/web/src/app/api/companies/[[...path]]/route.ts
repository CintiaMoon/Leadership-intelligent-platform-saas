import type { NextRequest } from "next/server";

import { forward } from "@/lib/server/bff";

type Context = { params: Promise<{ path?: string[] }> };

/**
 * The company operations the browser may use (F1, D-172). Only the operations listed in `lib/server/bff.ts` are
 * forwarded; reads are made by the server-rendered pages, so there is no GET handler here.
 */
async function handle(request: NextRequest, context: Context) {
  return forward(request, (await context.params).path ?? [], undefined, "companies");
}

export const POST = handle;
export const PATCH = handle;
export const DELETE = handle;
