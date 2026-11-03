import "server-only";
import { dataFromEnvelope, staffApi, staffToken } from "@/lib/staff-server";

export type AdminTable = {
  id: string;
  tableCode: string;
  publicAlias: string;
  capacity: number;
  status: "AVAILABLE" | "OUT_OF_SERVICE";
  activeVisit: boolean;
};
export type TableAdminResult =
  | { ok: true; data: AdminTable[] }
  | { ok: false; status: number };

const UUID = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i;

function validTable(item: unknown): item is AdminTable {
  if (!item || typeof item !== "object" || Array.isArray(item)) return false;
  const t = item as Record<string, unknown>;
  return typeof t.id === "string" && UUID.test(t.id) &&
    typeof t.tableCode === "string" && /^[A-Z0-9][A-Z0-9-]{0,39}$/.test(t.tableCode) &&
    typeof t.publicAlias === "string" && /^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(t.publicAlias) &&
    typeof t.capacity === "number" && Number.isInteger(t.capacity) &&
    t.capacity >= 1 && t.capacity <= 100 &&
    (t.status === "AVAILABLE" || t.status === "OUT_OF_SERVICE") &&
    typeof t.activeVisit === "boolean";
}

export async function adminTables(): Promise<TableAdminResult> {
  const token = await staffToken();
  if (!token) return { ok: false, status: 401 };
  const response = await staffApi("/api/v1/admin/tables", "GET", token);
  if (response.status !== 200) return { ok: false, status: response.status };
  const data = dataFromEnvelope(response.payload);
  if (!Array.isArray(data) || !data.every(validTable) || data.length > 250) {
    return { ok: false, status: 502 };
  }
  return { ok: true, data };
}
