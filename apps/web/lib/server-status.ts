export type ApiStatus = {
  service: string;
  database: string;
  configuredTables: number;
};

export async function fetchSystemStatus(): Promise<ApiStatus | null> {
  const baseUrl = process.env.API_INTERNAL_URL ?? "http://localhost:8080";
  try {
    const response = await fetch(new URL("/api/v1/system/status", baseUrl), {
      cache: "no-store",
      signal: AbortSignal.timeout(5000),
    });
    if (!response.ok) return null;
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
  } catch {
    return null;
  }
}
