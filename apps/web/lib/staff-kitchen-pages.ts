import "server-only";

import { dataFromEnvelope, isTicket, staffApi, staffToken } from "@/lib/staff-server";
import type { KitchenTicket } from "@/lib/staff-server";

export type KitchenPage = {
  page: number;
  pageSize: number;
  total: number;
  hasNext: boolean;
  items: KitchenTicket[];
};
export type KitchenPageResult =
  | { ok: true; data: KitchenPage }
  | { ok: false; status: number };

/** Only the Next.js server sends revocable staff credentials to the API. */
export async function kitchenPage(page: number): Promise<KitchenPageResult> {
  if (!Number.isInteger(page) || page < 0 || page > 1000)
    return { ok: false, status: 400 };
  const token = await staffToken();
  if (!token) return { ok: false, status: 401 };
  const response = await staffApi("/api/v1/kitchen/tickets/page?page=" + page, "GET", token);
  if (response.status !== 200) return { ok: false, status: response.status };
  const value = dataFromEnvelope(response.payload);
  if (typeof value !== "object" || value === null || Array.isArray(value))
    return { ok: false, status: 502 };
  const data = value as Record<string, unknown>;
  if (data.page !== page || data.pageSize !== 25 ||
      typeof data.total !== "number" || !Number.isSafeInteger(data.total) || data.total < 0 ||
      typeof data.hasNext !== "boolean" || !Array.isArray(data.items) ||
      data.items.length > 25 || data.items.length > data.total ||
      (data.items.length === 0 && data.hasNext) || !data.items.every(isTicket))
    return { ok: false, status: 502 };
  return { ok: true, data: data as KitchenPage };
}
