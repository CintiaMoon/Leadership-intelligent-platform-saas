import { readApi } from "./api";
import { COMPANY_ID, requireSession, signInAgain } from "./session";

export type CompanyDetails = { id: string; name: string; status: string; role: string; createdAt: string };

export type CompanyLoad =
  | { kind: "ok"; company: CompanyDetails; incoming: Headers; config: Awaited<ReturnType<typeof requireSession>>["config"] }
  | { kind: "unavailable" }
  | { kind: "error" };

/**
 * Reads one company for a page behind the shell. The id from the address is only a selector: anything that is not a
 * UUID never goes to the API, and a company the person cannot use looks the same as one that does not exist (D-175).
 * An ended session goes to sign in.
 */
export async function loadCompany(companyId: string): Promise<CompanyLoad> {
  const { incoming, config } = await requireSession();
  if (!COMPANY_ID.test(companyId)) {
    return { kind: "unavailable" };
  }
  const result = await readApi<CompanyDetails>(incoming, `/api/v1/companies/${companyId.toLowerCase()}`, config);
  if (result.ok) {
    return { kind: "ok", company: result.data, incoming, config };
  }
  if (result.status === 401) {
    signInAgain();
  }
  return result.status === 0 || result.status >= 500 ? { kind: "error" } : { kind: "unavailable" };
}
