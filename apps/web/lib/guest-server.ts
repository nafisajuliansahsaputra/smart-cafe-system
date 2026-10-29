import "server-only";

import { cookies } from "next/headers";

export const GUEST_COOKIE = "scs_guest_session";
export const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export const TABLE_ALIAS = /^[a-zA-Z0-9_-]{1,120}$/;

export type Result<T> = { ok: true; data: T } | { ok: false; status: number };
export type GuestVisit = { guestId: string; visitId: string; tableCode: string; displayName: string };
export type TableEntry = { tableCode: string; activeVisit: boolean };
export type MenuItem = { id: string; name: string; category: string; priceIdr: number; remainingQuantity: number | null };
export type VariantOption = { id: string; label: string; priceDeltaIdr: number };
export type VariantGroup = { id: string; name: string; required: boolean; options: VariantOption[] };
export type CartLine = {
  menuItemId: string;
  name: string;
  unitPriceIdr: number;
  quantity: number;
  lineTotalIdr: number;
  available: boolean;
  remainingQuantity: number | null;
  variantOptionIds: string[];
  variantLabels: string[];
};
export type Cart = { version: number; estimatedSubtotalIdr: number; lines: CartLine[] };
export type GuestOrder = { id: string; status: string; paymentStatus: string; totalIdr: number };
export type OrderDetail = GuestOrder & {
  createdAt: string;
  lines: {
    name: string; quantity: number; unitPriceIdr: number;
    lineTotalIdr: number; notes: string | null; variantLabel: string | null;
  }[];
};
export type Receipt = {
  id: string; number: number; orderId: string; totalIdr: number; currency: string;
  issuedAt: string; lines: { name: string; quantity: number; unitPriceIdr: number;
    lineTotalIdr: number; variantLabel: string | null }[];
};

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
function isNonnegativeMoney(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0;
}
function isQuantity(value: unknown): value is number {
  return typeof value === "number" && Number.isInteger(value) && value >= 1 && value <= 20;
}
function isId(value: unknown): value is string {
  return typeof value === "string" && UUID_PATTERN.test(value);
}
function nullableQuantity(value: unknown): value is number | null {
  return value === null || isNonnegativeMoney(value);
}
export function envelopeData(value: unknown): unknown {
  return isRecord(value) ? value.data : undefined;
}

export async function guestApi(
  path: string, method: "GET" | "POST" | "PUT",
  token?: string, body?: Record<string, unknown>, key?: string,
): Promise<{ status: number; payload: unknown }> {
  // Only call with fixed server-owned paths or previously UUID-validated path segments.
  const baseUrl = process.env.API_INTERNAL_URL ?? "http://localhost:8080";
  try {
    const response = await fetch(new URL(path, baseUrl), {
      method,
      headers: {
        Accept: "application/json",
        ...(body ? { "Content-Type": "application/json" } : {}),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(key ? { "Idempotency-Key": key } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
      cache: "no-store",
      signal: AbortSignal.timeout(6000),
    });
    let payload: unknown = null;
    try { payload = await response.json(); } catch { /* invalid upstream response */ }
    return { status: response.status, payload };
  } catch {
    // Do not log request bodies, credentials or access tokens.
    return { status: 0, payload: null };
  }
}

export async function guestToken(): Promise<string | null> {
  return (await cookies()).get(GUEST_COOKIE)?.value ?? null;
}

async function getData<T>(path: string, check: (data: unknown) => data is T, token?: string): Promise<Result<T>> {
  const response = await guestApi(path, "GET", token);
  if (response.status !== 200) return { ok: false, status: response.status };
  const data = envelopeData(response.payload);
  return check(data) ? { ok: true, data } : { ok: false, status: 502 };
}

function isVisit(data: unknown): data is GuestVisit {
  return isRecord(data) && isId(data.guestId) && isId(data.visitId) &&
    typeof data.tableCode === "string" && typeof data.displayName === "string";
}

export async function guestIdentity(): Promise<
  { kind: "signed-in"; visit: GuestVisit } | { kind: "signed-out" } | { kind: "unavailable" }
> {
  const token = await guestToken();
  if (!token) return { kind: "signed-out" };
  const result = await getData("/api/v1/me/visit", isVisit, token);
  if (result.ok) return { kind: "signed-in", visit: result.data };
  if (result.status === 0 || result.status >= 500) return { kind: "unavailable" };
  return { kind: "signed-out" };
}

export function getTableEntry(alias: string): Promise<Result<TableEntry>> {
  if (!TABLE_ALIAS.test(alias)) return Promise.resolve({ ok: false, status: 404 });
  return getData(`/api/v1/tables/${alias}/entry`, (data): data is TableEntry =>
    isRecord(data) && typeof data.tableCode === "string" &&
    typeof data.activeVisit === "boolean");
}
function isMenuItem(data: unknown): data is MenuItem {
  return isRecord(data) && isId(data.id) && typeof data.name === "string" &&
    typeof data.category === "string" && isNonnegativeMoney(data.priceIdr) &&
    nullableQuantity(data.remainingQuantity);
}
export function getMenu(): Promise<Result<MenuItem[]>> {
  return getData("/api/v1/menu", (data): data is MenuItem[] =>
    Array.isArray(data) && data.every(isMenuItem));
}
function isVariantOption(data: unknown): data is VariantOption {
  return isRecord(data) && isId(data.id) && typeof data.label === "string" &&
    typeof data.priceDeltaIdr === "number" && Number.isSafeInteger(data.priceDeltaIdr);
}
function isVariantGroup(data: unknown): data is VariantGroup {
  return isRecord(data) && isId(data.id) && typeof data.name === "string" &&
    typeof data.required === "boolean" && Array.isArray(data.options) &&
    data.options.every(isVariantOption);
}
export function getVariants(itemId: string): Promise<Result<VariantGroup[]>> {
  if (!UUID_PATTERN.test(itemId)) return Promise.resolve({ ok: false, status: 404 });
  return getData(`/api/v1/menu/${itemId}/variant-groups`, (data): data is VariantGroup[] =>
    Array.isArray(data) && data.every(isVariantGroup));
}
function isCartLine(data: unknown): data is CartLine {
  return isRecord(data) && isId(data.menuItemId) && typeof data.name === "string" &&
    isNonnegativeMoney(data.unitPriceIdr) && isQuantity(data.quantity) &&
    isNonnegativeMoney(data.lineTotalIdr) && typeof data.available === "boolean" &&
    nullableQuantity(data.remainingQuantity) && Array.isArray(data.variantOptionIds) &&
    data.variantOptionIds.every(isId) && Array.isArray(data.variantLabels) &&
    data.variantLabels.every((v: unknown) => typeof v === "string");
}
export function isCart(data: unknown): data is Cart {
  return isRecord(data) && isNonnegativeMoney(data.version) &&
    isNonnegativeMoney(data.estimatedSubtotalIdr) && Array.isArray(data.lines) &&
    data.lines.every(isCartLine);
}
export async function getCart(): Promise<Result<Cart>> {
  const token = await guestToken();
  return token ? getData("/api/v1/me/cart", isCart, token) : { ok: false, status: 401 };
}
function isGuestOrder(data: unknown): data is GuestOrder {
  return isRecord(data) && isId(data.id) && typeof data.status === "string" &&
    typeof data.paymentStatus === "string" && isNonnegativeMoney(data.totalIdr);
}
function isOrderLine(data: unknown): boolean {
  return isRecord(data) && typeof data.name === "string" &&
    isQuantity(data.quantity) && isNonnegativeMoney(data.unitPriceIdr) &&
    isNonnegativeMoney(data.lineTotalIdr) &&
    (data.notes === null || typeof data.notes === "string") &&
    (data.variantLabel === null || typeof data.variantLabel === "string");
}
export async function getOrders(): Promise<Result<GuestOrder[]>> {
  const token = await guestToken();
  return token ? getData("/api/v1/me/orders", (data): data is GuestOrder[] =>
    Array.isArray(data) && data.every(isGuestOrder), token) : { ok: false, status: 401 };
}
export async function getOrder(id: string): Promise<Result<OrderDetail>> {
  if (!UUID_PATTERN.test(id)) return { ok: false, status: 404 };
  const token = await guestToken();
  return token ? getData(`/api/v1/me/orders/${id}`, (data): data is OrderDetail => {
    if (!isGuestOrder(data)) return false;
    const detail: Record<string, unknown> = data as unknown as Record<string, unknown>;
    return typeof detail.createdAt === "string" &&
      Array.isArray(detail.lines) && detail.lines.every(isOrderLine);
  }, token)
    : { ok: false, status: 401 };
}
function isReceipt(data: unknown): data is Receipt {
  return isRecord(data) && isId(data.id) && isId(data.orderId) &&
    isNonnegativeMoney(data.number) && isNonnegativeMoney(data.totalIdr) &&
    data.currency === "IDR" && typeof data.issuedAt === "string" &&
    Array.isArray(data.lines) && data.lines.every((line: unknown) =>
      isRecord(line) && typeof line.name === "string" &&
      isQuantity(line.quantity) && isNonnegativeMoney(line.unitPriceIdr) &&
      isNonnegativeMoney(line.lineTotalIdr) &&
      (line.variantLabel === null || typeof line.variantLabel === "string"));
}
export async function getOrderReceipt(id: string): Promise<Result<Receipt>> {
  if (!UUID_PATTERN.test(id)) return { ok: false, status: 404 };
  const token = await guestToken();
  return token ? getData(`/api/v1/me/orders/${id}/receipt`, isReceipt, token)
    : { ok: false, status: 401 };
}
export function idr(value: number): string {
  return new Intl.NumberFormat("id-ID", {
    style: "currency", currency: "IDR", maximumFractionDigits: 0,
  }).format(value);
}
export function timeWib(value: string): string {
  const dt = new Date(value);
  return Number.isNaN(dt.valueOf()) ? "Waktu tidak diketahui" :
    new Intl.DateTimeFormat("id-ID", {
      timeZone: "Asia/Jakarta", dateStyle: "medium", timeStyle: "short",
    }).format(dt);
}
