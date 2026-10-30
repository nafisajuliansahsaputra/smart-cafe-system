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
  idempotencyKey?: string,
): Promise<ApiResponse> {
  const base = process.env.API_INTERNAL_URL ?? "http://localhost:8080";
  try {
    const response = await fetch(new URL(path, base), {
      method,
      headers: {
        Accept: "application/json",
        ...(body ? { "Content-Type": "application/json" } : {}),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(idempotencyKey ? { "Idempotency-Key": idempotencyKey } : {}),
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
  if (!isQueueOrder(data)) return { ok: false, status: 502 };
  const detail: Record<string, unknown> = data as unknown as Record<string, unknown>;
  if (
    typeof detail.subtotalIdr !== "number" ||
    typeof detail.taxIdr !== "number" ||
    typeof detail.serviceFeeIdr !== "number" ||
    typeof detail.paymentMethod !== "string" ||
    !Array.isArray(detail.lines) ||
    !detail.lines.every(
      (line: unknown) =>
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

export function canUseKitchen(roles: string[]): boolean {
  return roles.some((role) => ["KITCHEN", "MANAGER", "ADMIN"].includes(role));
}

export function canUseWaiter(roles: string[]): boolean {
  return roles.some((role) => ["WAITER", "CASHIER", "MANAGER", "ADMIN"].includes(role));
}

export type KitchenTicket = {
  id: string;
  orderId: string;
  tableCode: string;
  status: string;
  lines: { name: string; quantity: number; notes: string | null; variantLabel: string | null }[];
};

function isTicket(value: unknown): value is KitchenTicket {
  return (
    record(value) &&
    typeof value.id === "string" &&
    typeof value.orderId === "string" &&
    typeof value.tableCode === "string" &&
    ["ACCEPTED", "PREPARING", "READY"].includes(String(value.status)) &&
    Array.isArray(value.lines) &&
    value.lines.every(
      (line: unknown) =>
        record(line) &&
        typeof line.name === "string" &&
        typeof line.quantity === "number" && Number.isInteger(line.quantity) &&
        (line.notes === null || typeof line.notes === "string") &&
        (line.variantLabel === null || typeof line.variantLabel === "string"),
    )
  );
}

export async function kitchenTickets(): Promise<LoadResult<KitchenTicket[]>> {
  const token = await staffToken();
  if (!token) return { ok: false, status: 401 };
  const response = await staffApi("/api/v1/kitchen/tickets", "GET", token);
  if (response.status !== 200) return { ok: false, status: response.status };
  const data = dataFromEnvelope(response.payload);
  if (!Array.isArray(data) || !data.every(isTicket)) {
    return { ok: false, status: 502 };
  }
  return { ok: true, data };
}

export type WaiterTask = {
  orderId: string;
  paymentIntentId: string;
  receivedIdr: number | null;
  changeGivenIdr: number | null;
  tableCode: string;
  orderStatus: string;
  paymentStatus: string;
  totalIdr: number;
  createdAt: string;
  lines: { name: string; quantity: number; variantLabel: string | null; notes: string | null }[];
};

function isWaiterTask(value: unknown): value is WaiterTask {
  return (
    record(value) &&
    typeof value.orderId === "string" &&
    typeof value.paymentIntentId === "string" &&
    typeof value.tableCode === "string" &&
    ["READY", "SERVED"].includes(String(value.orderStatus)) &&
    ["AWAITING_CASH", "PAID"].includes(String(value.paymentStatus)) &&
    typeof value.totalIdr === "number" && Number.isSafeInteger(value.totalIdr) &&
    (value.receivedIdr === null || (typeof value.receivedIdr === "number" && Number.isSafeInteger(value.receivedIdr))) &&
    (value.changeGivenIdr === null || (typeof value.changeGivenIdr === "number" && Number.isSafeInteger(value.changeGivenIdr))) &&
    typeof value.createdAt === "string" &&
    Array.isArray(value.lines) &&
    value.lines.every(
      (line: unknown) =>
        record(line) &&
        typeof line.name === "string" &&
        typeof line.quantity === "number" && Number.isInteger(line.quantity) &&
        (line.variantLabel === null || typeof line.variantLabel === "string") &&
        (line.notes === null || typeof line.notes === "string"),
    )
  );
}

export async function waiterTasks(): Promise<LoadResult<WaiterTask[]>> {
  const token = await staffToken();
  if (!token) return { ok: false, status: 401 };
  const response = await staffApi("/api/v1/waiter/orders/tasks", "GET", token);
  if (response.status !== 200) return { ok: false, status: response.status };
  const data = dataFromEnvelope(response.payload);
  if (!Array.isArray(data) || !data.every(isWaiterTask)) {
    return { ok: false, status: 502 };
  }
  return { ok: true, data };
}

export type StaffTable = {
  tableId: string;
  tableCode: string;
  publicAlias: string;
  capacity: number;
  tableStatus: "AVAILABLE" | "OUT_OF_SERVICE";
  activeVisit: boolean;
  manageableVisitId: string | null;
  proofExpiresAt: string | null;
};
const staffUuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i;

function isStaffTable(value: unknown): value is StaffTable {
  return (
    record(value) &&
    typeof value.tableId === "string" && staffUuid.test(value.tableId) &&
    typeof value.tableCode === "string" &&
    typeof value.publicAlias === "string" &&
    typeof value.capacity === "number" && Number.isInteger(value.capacity) && value.capacity > 0 &&
    (value.tableStatus === "AVAILABLE" || value.tableStatus === "OUT_OF_SERVICE") &&
    typeof value.activeVisit === "boolean" &&
    (value.manageableVisitId === null ||
      (typeof value.manageableVisitId === "string" && staffUuid.test(value.manageableVisitId))) &&
    (value.proofExpiresAt === null || typeof value.proofExpiresAt === "string")
  );
}

export async function staffTables(): Promise<LoadResult<StaffTable[]>> {
  const token = await staffToken();
  if (!token) return { ok: false, status: 401 };
  const result = await staffApi("/api/v1/waiter/tables", "GET", token);
  if (result.status !== 200) return { ok: false, status: result.status };
  const data = dataFromEnvelope(result.payload);
  if (!Array.isArray(data) || !data.every(isStaffTable)) {
    return { ok: false, status: 502 };
  }
  return { ok: true, data };
}


export type DailyCashReport = {
  date: string;
  timezone: "Asia/Jakarta";
  currency: "IDR";
  paidOrders: number;
  grossCollectedIdr: number;
  topItems: { name: string; quantity: number; grossIdr: number }[];
};

function safeCashNumber(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0;
}

function isDailyCashReport(data: unknown): data is DailyCashReport {
  return record(data) && typeof data.date === "string" &&
    /^\d{4}-\d{2}-\d{2}$/.test(data.date) &&
    data.timezone === "Asia/Jakarta" && data.currency === "IDR" &&
    safeCashNumber(data.paidOrders) && safeCashNumber(data.grossCollectedIdr) &&
    Array.isArray(data.topItems) && data.topItems.length <= 10 &&
    data.topItems.every((item: unknown) => record(item) &&
      typeof item.name === "string" &&
      safeCashNumber(item.quantity) && safeCashNumber(item.grossIdr));
}

export async function dailyCashReport(date: string): Promise<LoadResult<DailyCashReport>> {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
    return { ok: false, status: 400 };
  }
  const token = await staffToken();
  if (!token) return { ok: false, status: 401 };
  const response = await staffApi(
    "/api/v1/ops/reports/cash-daily?date=" + encodeURIComponent(date),
    "GET", token,
  );
  if (response.status !== 200) return { ok: false, status: response.status };
  const data = dataFromEnvelope(response.payload);
  return isDailyCashReport(data) ? { ok: true, data } : { ok: false, status: 502 };
}
