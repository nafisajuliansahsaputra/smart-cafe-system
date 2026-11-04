import "server-only";

import { dataFromEnvelope, staffApi, staffToken } from "@/lib/staff-server";

export type RegistryTable = {
  tableId: string;
  tableCode: string;
  publicAlias: string;
  capacity: number;
  tableStatus: "AVAILABLE" | "OUT_OF_SERVICE";
  activeVisit: boolean;
};
export type RegistryPage = {
  page: number;
  pageSize: number;
  hasNext: boolean;
  total: number;
  items: RegistryTable[];
};
export type RegistryResult = { ok: true; data: RegistryPage } |
  { ok: false; status: number };

const UUID = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i;
const CODE = /^[A-Z0-9][A-Z0-9_-]{0,39}$/;
const ALIAS = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;

function isTable(value: unknown): value is RegistryTable {
  if (typeof value !== "object" || value === null || Array.isArray(value))
    return false;
  const row = value as Record<string, unknown>;
  return typeof row.tableId === "string" && UUID.test(row.tableId) &&
    typeof row.tableCode === "string" && CODE.test(row.tableCode) &&
    typeof row.publicAlias === "string" && row.publicAlias.length <= 100 &&
    ALIAS.test(row.publicAlias) &&
    typeof row.capacity === "number" && Number.isInteger(row.capacity) &&
    row.capacity >= 1 && row.capacity <= 30 &&
    (row.tableStatus === "AVAILABLE" || row.tableStatus === "OUT_OF_SERVICE") &&
    typeof row.activeVisit === "boolean";
}

export async function tableRegistry(page: number, q: string): Promise<RegistryResult> {
  if (!Number.isInteger(page) || page < 0 || page > 1000 ||
    q.length > 60 || !/^[A-Za-z0-9_-]*$/.test(q)) {
    return { ok: false, status: 400 };
  }
  const token = await staffToken();
  if (!token) return { ok: false, status: 401 };
  const params = new URLSearchParams({ page: String(page), q });
  const response = await staffApi("/api/v1/admin/tables/registry?" + params, "GET", token);
  if (response.status !== 200) return { ok: false, status: response.status };
  const value = dataFromEnvelope(response.payload);
  if (!value || typeof value !== "object" || Array.isArray(value))
    return { ok: false, status: 502 };
  const data = value as Record<string, unknown>;
  if (data.page !== page || data.pageSize !== 25 ||
    typeof data.hasNext !== "boolean" ||
    typeof data.total !== "number" || !Number.isSafeInteger(data.total) ||
    data.total < 0 || !Array.isArray(data.items) || data.items.length > 25 ||
    !data.items.every(isTable) ||
    (data.items.length === 0 && data.hasNext) ||
    (data.items.length > data.total)) {
    return { ok: false, status: 502 };
  }
  return { ok: true, data: data as RegistryPage };
}
