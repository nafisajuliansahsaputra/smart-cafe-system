import { trustedBackendHeaders } from "@/lib/vercel-trusted-backend";

export type ApiStatus = {
  service: string;
  database: string;
  configuredTables: number;
};

export async function fetchSystemStatus(): Promise<ApiStatus | null> {
  const baseUrl = process.env.API_INTERNAL_URL ?? "http://localhost:8080";
  try {
    const response = await fetch(new URL("/api/v1/system/status", baseUrl), {
      headers: await trustedBackendHeaders(baseUrl),
      cache: "no-store",
      signal: AbortSignal.timeout(5000),
    });
    if (!response.ok) {
      // Status code only. Never emit upstream URL, response body or headers.
      console.warn("SCS_REVIEW_STATUS_UPSTREAM_HTTP", response.status);
      return null;
    }
    const envelope: unknown = await response.json();
    if (
      typeof envelope !== "object" ||
      envelope === null ||
      !("data" in envelope) ||
      typeof envelope.data !== "object" ||
      envelope.data === null
    ) {
      return null;
    }
    const data = envelope.data;
    if (
      !("database" in data) ||
      data.database !== "connected" ||
      !("service" in data) ||
      typeof data.service !== "string" ||
      !("configuredTables" in data) ||
      typeof data.configuredTables !== "number"
    ) {
      return null;
    }
    return {
      service: data.service,
      database: data.database,
      configuredTables: data.configuredTables,
    };
  } catch (error) {
    // Error CLASS only, never error.message which may contain the URL.
    console.warn("SCS_REVIEW_STATUS_UPSTREAM_EXCEPTION",
      error instanceof Error ? error.name : "unknown",
      process.env.API_INTERNAL_URL ? "configured" : "missing");
    return null;
  }
}
