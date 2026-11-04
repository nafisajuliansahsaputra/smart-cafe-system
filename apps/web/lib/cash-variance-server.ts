import "server-only";

import { dataFromEnvelope, staffApi, staffToken } from "@/lib/staff-server";

type Load<T> = { ok: true; data: T } | { ok: false; status: number };

export type VarianceCase = {
  shiftId: string;
  closedAt: string;
  expectedDrawerIdr: number;
  countedCashIdr: number;
  differenceIdr: number;
  latestAction: string | null;
  latestActionAt: string | null;
  latestActorId: string | null;
};
export type VariancePage = { page: number; hasNext: boolean; items: VarianceCase[] };
export type VarianceAction = {
  id: string;
  shiftId: string;
  actorId: string;
  actionCode: string;
  note: string;
  createdAt: string;
};

function object(x: unknown): x is Record<string, unknown> {
  return x !== null && typeof x === "object" && !Array.isArray(x);
}
function uuid(x: unknown): x is string {
  return typeof x === "string" &&
    /^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i.test(x);
}
function int(x: unknown): x is number {
  return typeof x === "number" && Number.isSafeInteger(x);
}
function time(x: unknown): x is string {
  return typeof x === "string" && Number.isFinite(Date.parse(x));
}
const ACTIONS = ["REQUEST_RECOUNT", "DOCUMENT_EVIDENCE", "ESCALATE"];
function caseRow(x: unknown): x is VarianceCase {
  return object(x) && uuid(x.shiftId) && time(x.closedAt) &&
    int(x.expectedDrawerIdr) && x.expectedDrawerIdr >= 0 &&
    int(x.countedCashIdr) && x.countedCashIdr >= 0 &&
    int(x.differenceIdr) && x.differenceIdr !== 0 &&
    x.countedCashIdr - x.expectedDrawerIdr === x.differenceIdr &&
    (x.latestAction === null || ACTIONS.includes(String(x.latestAction))) &&
    (x.latestActionAt === null || time(x.latestActionAt)) &&
    (x.latestActorId === null || uuid(x.latestActorId));
}
function actionRow(x: unknown): x is VarianceAction {
  return object(x) && uuid(x.id) && uuid(x.shiftId) &&
    uuid(x.actorId) && ACTIONS.includes(String(x.actionCode)) &&
    typeof x.note === "string" && x.note.length <= 300 &&
    time(x.createdAt);
}

export async function cashVarianceCases(page: number): Promise<Load<VariancePage>> {
  if (!Number.isInteger(page) || page < 0 || page > 1000) {
    return { ok: false, status: 400 };
  }
  const token = await staffToken();
  if (!token) return { ok: false, status: 401 };
  const response = await staffApi("/api/v1/ops/cash-variance-reviews?page=" + page,
    "GET",token);
  if (response.status !== 200) return { ok: false, status: response.status };
  const x = dataFromEnvelope(response.payload);
  if (!object(x) || x.page !== page ||
      typeof x.hasNext !== "boolean" || !Array.isArray(x.items) ||
      x.items.length > 20 || !x.items.every(caseRow)) {
    return { ok: false, status: 502 };
  }
  return { ok: true, data: x as VariancePage };
}

export async function cashVarianceHistory(shiftId: string): Promise<Load<VarianceAction[]>> {
  if (!uuid(shiftId)) return { ok: false, status: 400 };
  const token = await staffToken();
  if (!token) return { ok: false, status: 401 };
  const response = await staffApi(
    "/api/v1/ops/cash-variance-reviews/" + shiftId + "/history", "GET",token);
  if (response.status !== 200) return { ok: false, status: response.status };
  const x = dataFromEnvelope(response.payload);
  return Array.isArray(x) && x.length <= 100 && x.every(actionRow) &&
      x.every((entry: VarianceAction) => entry.shiftId === shiftId)
    ? { ok: true, data: x } : { ok: false, status: 502 };
}
