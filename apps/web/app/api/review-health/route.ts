import { NextResponse } from "next/server";

export const dynamic = "force-dynamic";

/**
 * Synthetic preview health probe only. No database URL, credentials, tokens,
 * upstream HTML, or user data are returned to the caller or written to logs.
 */
export async function GET() {
  const headers = { "Cache-Control": "no-store, max-age=0" };
  if (process.env.VERCEL_ENV !== "preview") {
    return new Response(null, { status: 404, headers });
  }
  const base = process.env.API_INTERNAL_URL;
  if (!base) {
    return NextResponse.json(
      { backend: "unavailable", reason: "not_configured" },
      { status: 503, headers },
    );
  }
  try {
    const response = await fetch(new URL("/api/v1/system/status", base), {
      cache: "no-store",
      redirect: "manual",
      signal: AbortSignal.timeout(12000),
      headers: { Accept: "application/json" },
    });
    if (!response.ok) {
      return NextResponse.json(
        {
          backend: "unavailable",
          reason: response.status >= 300 && response.status < 400
            ? "upstream_redirect" : "upstream_http",
          upstreamStatus: response.status,
        },
        { status: 503, headers },
      );
    }
    const payload: unknown = await response.json();
    if (
      typeof payload === "object" && payload !== null &&
      "data" in payload && typeof payload.data === "object" &&
      payload.data !== null && "database" in payload.data &&
      payload.data.database === "connected" &&
      "configuredTables" in payload.data &&
      typeof payload.data.configuredTables === "number"
    ) {
      return NextResponse.json({
        backend: "connected",
        configuredTables: payload.data.configuredTables,
      }, { headers });
    }
    return NextResponse.json(
      { backend: "unavailable", reason: "unexpected_response" },
      { status: 503, headers },
    );
  } catch (error) {
    return NextResponse.json({
      backend: "unavailable",
      reason: error instanceof Error && error.name === "TimeoutError"
        ? "timeout" : "connection_error",
    }, { status: 503, headers });
  }
}
