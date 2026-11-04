import "server-only";

import { dataFromEnvelope, staffApi, staffToken } from "@/lib/staff-server";
import type { QueueOrder } from "@/lib/staff-server";

export type OccOrderPage = {
  page: number;
  pageSize: number;
  hasNext: boolean;
  total: number;
  items: QueueOrder[];
};
export type OccOrderPageResult =
  | { ok: true; data: OccOrderPage }
  | { ok: false; status: number };

export const orderStatuses = [
  "", "SUBMITTED", "ACCEPTED", "PREPARING", "READY", "SERVED", "REJECTED", "CANCELLED",
] as const;
export const paymentStatuses = [
  "", "AWAITING_CASH", "UNPAID", "PENDING", "PAID",
  "PARTIALLY_REFUNDED", "REFUNDED", "DISPUTED",
] as const;

const uuid = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i;
function isOrder(value: unknown): value is QueueOrder {
  if (typeof value !== "object" || value === null || Array.isArray(value))
    return false;
  const o = value as Record<string, unknown>;
  return typeof o.id === "string" && uuid.test(o.id) &&
    typeof o.tableCode === "string" && o.tableCode.length > 0 && o.tableCode.length <= 40 &&
    typeof o.orderStatus === "string" && orderStatuses.includes(o.orderStatus as typeof orderStatuses[number]) &&
    o.orderStatus !== "" &&
    typeof o.paymentStatus === "string" && paymentStatuses.includes(o.paymentStatus as typeof paymentStatuses[number]) &&
    o.paymentStatus !== "" &&
    typeof o.totalIdr === "number" && Number.isSafeInteger(o.totalIdr) && o.totalIdr >= 0 &&
    typeof o.createdAt === "string" && !Number.isNaN(Date.parse(o.createdAt));
}

/** Server-only bearer-bound OCC page. A malformed response is an error, never an empty queue. */
export async function occOrderPage(page: number, status: string, paymentStatus: string, tableCode: string):
  Promise<OccOrderPageResult> {
  if (!Number.isInteger(page) || page < 0 || page > 1000 ||
      !orderStatuses.includes(status as typeof orderStatuses[number]) ||
      !paymentStatuses.includes(paymentStatus as typeof paymentStatuses[number]) ||
      tableCode.length > 40) return { ok: false, status: 400 };
  const token = await staffToken();
  if (!token) return { ok: false, status: 401 };
  const params = new URLSearchParams({
    page: String(page),
    ...(status ? { status } : {}),
    ...(paymentStatus ? { paymentStatus } : {}),
    ...(tableCode ? { tableCode } : {}),
  });
  const response = await staffApi("/api/v1/ops/orders/page?" + params, "GET", token);
  if (response.status !== 200) return { ok: false, status: response.status };
  const value = dataFromEnvelope(response.payload);
  if (typeof value !== "object" || value === null || Array.isArray(value))
    return { ok: false, status: 502 };
  const data = value as Record<string, unknown>;
  if (data.page !== page || data.pageSize !== 25 ||
      typeof data.total !== "number" || !Number.isSafeInteger(data.total) || data.total < 0 ||
      typeof data.hasNext !== "boolean" || !Array.isArray(data.items) ||
      data.items.length > 25 || !data.items.every(isOrder) ||
      data.items.length > data.total || (data.items.length === 0 && data.hasNext)) {
    return { ok: false, status: 502 };
  }
  return { ok: true, data: data as OccOrderPage };
}
