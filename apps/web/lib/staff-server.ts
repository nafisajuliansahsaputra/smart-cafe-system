import "server-only";

import { cookies } from "next/headers";

export const STAFF_COOKIE = "scs_staff_session";

export type Staff = { id: string; login: string; roles: string[] };
export type QueueOrder = {
  id: string;
  tableCode: string;
  orderStatus: string;
  paymentStatus: string;
  totalIdr: number;
  createdAt: string;
};
export type OrderDetail = QueueOrder & {
  subtotalIdr: number;
  taxIdr: number;
  serviceFeeIdr: number;
  paymentMethod: string;
  lines: {
    name: string;
    quantity: number;
    unitPriceIdr: number;
    lineTotalIdr: number;
    notes: string | null;
    variantLabel: string | null;
  }[];
};

type ApiResponse = { status: number; payload: unknown };
type LoadResult<T> = { ok: true; data: T } | { ok: false; status: number };

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function dataFromEnvelope(payload: unknown): unknown {
  return record(payload) ? payload.data : undefined;
}

// Fixed, server-owned backend paths only. This is not an open proxy.
export async function staffApi(
  path: string,
  method: "GET" | "POST",
  token?: string,
  body?: Record<string, unknown>,
): Promise<ApiResponse> {
  const base = process.env.API_INTERNAL_URL ?? "http://localhost:8080";
  try {
    const response = await fetch(new URL(path, base), {
      method,
      headers: {
        Accept: "application/json",
        ...(body ? { "Content-Type": "application/json" } : {}),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
      cache: "no-store",
      signal: AbortSignal.timeout(6000),
    });
    let payload: unknown;
    try {
      payload = await response.json();
    } catch {
      payload = null;
    }
    return { status: response.status, payload };
  } catch {
    // No API errors or request headers (especially bearer tokens) enter logs.
    return { status: 0, payload: null };
  }
}

export async function staffToken(): Promise<string | null> {
  return (await cookies()).get(STAFF_COOKIE)?.value ?? null;
}

export function canUseOcc(roles: string[]): boolean {
  return roles.some((role) => ["CASHIER", "MANAGER", "ADMIN"].includes(role));
}

export async function staffIdentity(): Promise<
  | { kind: "signed-out" }
  | { kind: "unavailable" }
  | { kind: "signed-in"; staff: Staff }
> {
  const token = await staffToken();
  if (!token) return { kind: "signed-out" };
  const response = await staffApi("/api/v1/staff/auth/me", "GET", token);
  if (response.status === 0 || response.status >= 500) {
    return { kind: "unavailable" };
  }
  if (response.status !== 200) return { kind: "signed-out" };
  const data = dataFromEnvelope(response.payload);
  if (
    !record(data) ||
    typeof data.id !== "string" ||
    typeof data.login !== "string" ||
    !Array.isArray(data.roles) ||
    !data.roles.every((role) => typeof role === "string")
  ) {
    return { kind: "unavailable" };
  }
  return {
    kind: "signed-in",
    staff: { id: data.id, login: data.login, roles: data.roles },
  };
}

export async function orderQueue(filters: URLSearchParams): Promise<LoadResult<QueueOrder[]>> {
  const token = await staffToken();
  if (!token) return { ok: false, status: 401 };
  const search = filters.toString();
  const response = await staffApi(
    `/api/v1/ops/orders${search ? `?${search}` : ""}`,
    "GET",
    token,
  );
  const data = dataFromEnvelope(response.payload);
  if (response.status !== 200) return { ok: false, status: response.status };
  if (!Array.isArray(data) || !data.every(isQueueOrder)) {
    return { ok: false, status: 502 };
  }
  return { ok: true, data };
}

function isQueueOrder(value: unknown): value is QueueOrder {
  return (
    record(value) &&
    typeof value.id === "string" &&
    typeof value.tableCode === "string" &&
    typeof value.orderStatus === "string" &&
    typeof value.paymentStatus === "string" &&
    typeof value.totalIdr === "number" &&
    typeof value.createdAt === "string"
  );
}

export async function orderDetail(id: string): Promise<LoadResult<OrderDetail>> {
  const token = await staffToken();
  if (!token) return { ok: false, status: 401 };
  const response = await staffApi(`/api/v1/ops/orders/${id}`, "GET", token);
  const data = dataFromEnvelope(response.payload);
  if (response.status !== 200) return { ok: false, status: response.status };
  if (
    !isQueueOrder(data) ||
    !record(data) ||
    typeof data.subtotalIdr !== "number" ||
    typeof data.taxIdr !== "number" ||
    typeof data.serviceFeeIdr !== "number" ||
    typeof data.paymentMethod !== "string" ||
    !Array.isArray(data.lines) ||
    !data.lines.every(
      (line) =>
        record(line) &&
        typeof line.name === "string" &&
        typeof line.quantity === "number" &&
        typeof line.unitPriceIdr === "number" &&
        typeof line.lineTotalIdr === "number" &&
        (line.notes === null || typeof line.notes === "string") &&
        (line.variantLabel === null || typeof line.variantLabel === "string"),
    )
  ) {
    return { ok: false, status: 502 };
  }
  return { ok: true, data: data as OrderDetail };
}

export function idr(value: number): string {
  return new Intl.NumberFormat("id-ID", {
    style: "currency",
    currency: "IDR",
    maximumFractionDigits: 0,
  }).format(value);
}

export function localTime(value: string): string {
  const time = new Date(value);
  return Number.isNaN(time.getTime())
    ? "Waktu tidak tersedia"
    : new Intl.DateTimeFormat("id-ID", {
        dateStyle: "medium",
        timeStyle: "short",
        timeZone: "Asia/Jakarta",
      }).format(time);
}
