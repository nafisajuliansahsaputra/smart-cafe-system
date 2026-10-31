import "server-only";

import { dataFromEnvelope, staffApi, staffToken } from "@/lib/staff-server";

type LoadResult<T> = { ok: true; data: T } | { ok: false; status: number };
export type Shift = {
  id: string;
  state: "OPEN" | "CLOSED";
  openedBy: string;
  openedAt: string;
  openingFloatIdr: number;
  recordedCashIdr: number;
  expectedDrawerIdr: number;
  closedAt: string | null;
  closedBy: string | null;
  countedCashIdr: number | null;
  differenceIdr: number | null;
  closingReason: string | null;
};
export type ShiftDashboard = { active: Shift | null; recent: Shift[] };

function record(x: unknown): x is Record<string, unknown> {
  return x !== null && typeof x === "object" && !Array.isArray(x);
}
function integer(x: unknown): x is number {
  return typeof x === "number" && Number.isSafeInteger(x);
}
function time(x: unknown): x is string {
  return typeof x === "string" && Number.isFinite(Date.parse(x));
}
function uuid(x: unknown): x is string {
  return typeof x === "string" &&
    /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(x);
}
function shift(x: unknown): x is Shift {
  if (!record(x) || !uuid(x.id) || !uuid(x.openedBy) || !time(x.openedAt) ||
      !integer(x.openingFloatIdr) || x.openingFloatIdr < 0 ||
      !integer(x.recordedCashIdr) || x.recordedCashIdr < 0 ||
      !integer(x.expectedDrawerIdr) || x.expectedDrawerIdr < 0 ||
      x.openingFloatIdr + x.recordedCashIdr !== x.expectedDrawerIdr) return false;
  if (x.state === "OPEN") {
    return x.closedAt === null && x.closedBy === null &&
      x.countedCashIdr === null && x.differenceIdr === null &&
      x.closingReason === null;
  }
  return x.state === "CLOSED" && time(x.closedAt) && uuid(x.closedBy) &&
    integer(x.countedCashIdr) && x.countedCashIdr >= 0 &&
    integer(x.differenceIdr) && x.differenceIdr ===
      x.countedCashIdr - x.expectedDrawerIdr &&
    typeof x.closingReason === "string";
}

export async function cashShiftDashboard(): Promise<LoadResult<ShiftDashboard>> {
  const token = await staffToken();
  if (!token) return { ok: false, status: 401 };
  const response = await staffApi("/api/v1/ops/cash-shifts", "GET", token);
  if (response.status !== 200) return { ok: false, status: response.status };
  const payload = dataFromEnvelope(response.payload);
  if (!record(payload) || (payload.active !== null && !shift(payload.active)) ||
      !Array.isArray(payload.recent) || payload.recent.length > 10 ||
      !payload.recent.every((item: unknown) => shift(item) && item.state === "CLOSED")) {
    return { ok: false, status: 502 };
  }
  return { ok: true, data: { active: payload.active as Shift | null,
    recent: payload.recent as Shift[] } };
}
