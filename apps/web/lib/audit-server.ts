import "server-only";

import { dataFromEnvelope, staffApi, staffToken } from "@/lib/staff-server";

export type AuditEvent = {
  id: string;
  operation: string;
  resourceType: string;
  resourceId: string;
  actor: string;
  occurredAt: string;
};
export type AuditPage = {
  page: number;
  pageSize: number;
  hasNext: boolean;
  items: AuditEvent[];
};
export type AuditResult = { ok: true; data: AuditPage } | { ok: false; status: number };

const UUID = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i;
const safeText = (value: unknown, max: number): value is string =>
  typeof value === "string" && value.length > 0 && value.length <= max &&
  !/[\x00-\x1f\x7f]/.test(value);

function isEvent(value: unknown): value is AuditEvent {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return false;
  const e = value as Record<string, unknown>;
  return typeof e.id === "string" && UUID.test(e.id) &&
    safeText(e.operation, 120) && safeText(e.resourceType, 80) &&
    typeof e.resourceId === "string" && UUID.test(e.resourceId) &&
    safeText(e.actor, 120) && typeof e.occurredAt === "string" &&
    !Number.isNaN(Date.parse(e.occurredAt));
}

export async function auditEvents(page: number, q: string): Promise<AuditResult> {
  if (!Number.isInteger(page) || page < 0 || page > 1000 ||
    q.length > 60 || !/^[A-Za-z0-9_-]*$/.test(q)) {
    return { ok: false, status: 400 };
  }
  const token = await staffToken();
  if (!token) return { ok: false, status: 401 };
  const query = new URLSearchParams({ page: String(page), q });
  const response = await staffApi("/api/v1/admin/audit-events?" + query, "GET", token);
  if (response.status !== 200) return { ok: false, status: response.status };
  const data = dataFromEnvelope(response.payload);
  if (!data || typeof data !== "object" || Array.isArray(data)) {
    return { ok: false, status: 502 };
  }
  const d = data as Record<string, unknown>;
  if (d.page !== page || d.pageSize !== 25 || typeof d.hasNext !== "boolean" ||
    !Array.isArray(d.items) || d.items.length > 25 || !d.items.every(isEvent)) {
    return { ok: false, status: 502 };
  }
  return { ok: true, data: d as AuditPage };
}
