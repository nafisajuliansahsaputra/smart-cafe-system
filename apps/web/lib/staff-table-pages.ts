import "server-only";

import {
  dataFromEnvelope, staffApi, staffToken,
} from "@/lib/staff-server";
import type { StaffTable } from "@/lib/staff-server";

export type StaffTablePage = {
  page: number;
  pageSize: number;
  total: number;
  hasNext: boolean;
  items: StaffTable[];
};
export type StaffTablePageResult =
  | { ok: true; data: StaffTablePage }
  | { ok: false; status: number };

const uuid = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i;
function isRow(v: unknown): v is StaffTable {
  if (typeof v !== "object" || v === null || Array.isArray(v)) return false;
  const row = v as Record<string, unknown>;
  return typeof row.tableId === "string" && uuid.test(row.tableId) &&
    typeof row.tableCode === "string" && row.tableCode.length > 0 &&
    row.tableCode.length <= 40 &&
    typeof row.publicAlias === "string" && /^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(row.publicAlias) &&
    typeof row.capacity === "number" && Number.isInteger(row.capacity) &&
    row.capacity >= 1 && row.capacity <= 30 &&
    (row.tableStatus === "AVAILABLE" || row.tableStatus === "OUT_OF_SERVICE") &&
    typeof row.activeVisit === "boolean" &&
    (row.manageableVisitId === null ||
      (typeof row.manageableVisitId === "string" && uuid.test(row.manageableVisitId))) &&
    (row.proofExpiresAt === null ||
      (typeof row.proofExpiresAt === "string" && !Number.isNaN(Date.parse(row.proofExpiresAt)))) &&
    (!row.manageableVisitId || row.activeVisit);
}

/** Fetch one authorized server-side page; never expose staff bearer to client JS. */
export async function staffTablePage(page: number, q: string): Promise<StaffTablePageResult> {
  if (!Number.isInteger(page) || page < 0 || page > 1000 ||
      q.length > 60 || !/^[A-Za-z0-9_-]*$/.test(q)) {
    return { ok: false, status: 400 };
  }
  const token = await staffToken();
  if (!token) return { ok: false, status: 401 };
  const params = new URLSearchParams({ page: String(page), q });
  const response = await staffApi("/api/v1/waiter/tables/page?" + params, "GET", token);
  if (response.status !== 200) return { ok: false, status: response.status };
  const value = dataFromEnvelope(response.payload);
  if (typeof value !== "object" || value === null || Array.isArray(value))
    return { ok: false, status: 502 };
  const data = value as Record<string, unknown>;
  if (data.page !== page || data.pageSize !== 25 ||
      typeof data.total !== "number" || !Number.isSafeInteger(data.total) || data.total < 0 ||
      typeof data.hasNext !== "boolean" || !Array.isArray(data.items) ||
      data.items.length > 25 || !data.items.every(isRow) ||
      data.items.length > data.total ||
      (data.items.length === 0 && data.hasNext)) {
    return { ok: false, status: 502 };
  }
  return { ok: true, data: data as StaffTablePage };
}
