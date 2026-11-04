import "server-only";

type CursorResponse = { status: number; cursor: number | null };

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** Fixed, server-owned backend cursor path. Never accepts browser-provided URLs. */
export async function readLiveCursor(
  path: string,
  token: string,
  after?: number,
  signal?: AbortSignal,
): Promise<CursorResponse> {
  const base = process.env.API_INTERNAL_URL ?? "http://localhost:8080";
  const endpoint = new URL(path, base);
  if (after !== undefined) endpoint.searchParams.set("after", String(after));

  // Upstream long polls finish <=14s. Give HTTP stack breathing room.
  const timeout = AbortSignal.timeout(20500);
  const combined = signal ? AbortSignal.any([signal, timeout]) : timeout;
  try {
    const response = await fetch(endpoint, {
      method: "GET",
      headers: { Accept: "application/json", Authorization: `Bearer ${token}` },
      cache: "no-store",
      signal: combined,
    });
    const payload: unknown = await response.json().catch(() => null);
    const data = isRecord(payload) ? payload.data : null;
    const cursor = isRecord(data) ? data.cursor : null;
    if (response.status !== 200 || typeof cursor !== "number" ||
        !Number.isSafeInteger(cursor) || cursor < 0) {
      return { status: response.status === 200 ? 502 : response.status, cursor: null };
    }
    return { status: 200, cursor };
  } catch {
    return { status: 0, cursor: null };
  }
}
