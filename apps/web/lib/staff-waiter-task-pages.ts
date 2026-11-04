import "server-only";

import { dataFromEnvelope, isWaiterTask, staffApi, staffToken } from "@/lib/staff-server";
import type { WaiterTask } from "@/lib/staff-server";

export type WaiterTaskPage = {
  page: number;
  pageSize: number;
  total: number;
  hasNext: boolean;
  items: WaiterTask[];
};
export type WaiterTaskPageResult =
  | { ok: true; data: WaiterTaskPage }
  | { ok: false; status: number };

/** Credential stays in server cookies; never pass it to the browser. */
export async function waiterTaskPage(page: number): Promise<WaiterTaskPageResult> {
  if (!Number.isInteger(page) || page < 0 || page > 1000) {
    return { ok: false, status: 400 };
  }
  const token = await staffToken();
  if (!token) return { ok: false, status: 401 };
  const response = await staffApi("/api/v1/waiter/orders/tasks/page?page=" + page, "GET", token);
  if (response.status !== 200) return { ok: false, status: response.status };
  const payload = dataFromEnvelope(response.payload);
  if (typeof payload !== "object" || payload === null || Array.isArray(payload)) {
    return { ok: false, status: 502 };
  }
  const data = payload as Record<string, unknown>;
  if (data.page !== page || data.pageSize !== 25 ||
      typeof data.total !== "number" || !Number.isSafeInteger(data.total) || data.total < 0 ||
      typeof data.hasNext !== "boolean" ||
      !Array.isArray(data.items) || data.items.length > 25 ||
      data.items.length > data.total || !data.items.every(isWaiterTask) ||
      (data.items.length === 0 && data.hasNext)) {
    return { ok: false, status: 502 };
  }
  return { ok: true, data: data as WaiterTaskPage };
}
