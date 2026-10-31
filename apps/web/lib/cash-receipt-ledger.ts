import "server-only";

import { dataFromEnvelope, staffApi, staffToken } from "@/lib/staff-server";

type LoadResult<T> = { ok: true; data: T } | { ok: false; status: number };

export type CashReceiptRow = {
  receiptId: string;
  receiptNumber: number;
  orderId: string;
  tableCode: string;
  totalIdr: number;
  issuedAt: string;
};

export type CashReceiptPage = {
  date: string;
  timezone: "Asia/Jakarta";
  currency: "IDR";
  page: number;
  hasNext: boolean;
  items: CashReceiptRow[];
};

function object(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function safeInt(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0;
}

function isRow(value: unknown): value is CashReceiptRow {
  return object(value) &&
    typeof value.receiptId === "string" &&
    /^[0-9a-f-]{36}$/i.test(value.receiptId) &&
    safeInt(value.receiptNumber) &&
    typeof value.orderId === "string" &&
    /^[0-9a-f-]{36}$/i.test(value.orderId) &&
    typeof value.tableCode === "string" && value.tableCode.length <= 40 &&
    safeInt(value.totalIdr) &&
    typeof value.issuedAt === "string" &&
    Number.isFinite(Date.parse(value.issuedAt));
}

function isPage(value: unknown, date: string, page: number): value is CashReceiptPage {
  return object(value) &&
    value.date === date && value.page === page &&
    value.timezone === "Asia/Jakarta" && value.currency === "IDR" &&
    typeof value.hasNext === "boolean" &&
    Array.isArray(value.items) && value.items.length <= 20 &&
    value.items.every(isRow);
}

export async function dailyCashReceipts(
  date: string, page: number,
): Promise<LoadResult<CashReceiptPage>> {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date) ||
      !Number.isSafeInteger(page) || page < 0 || page > 1000) {
    return { ok: false, status: 400 };
  }
  const token = await staffToken();
  if (!token) return { ok: false, status: 401 };
  const response = await staffApi(
    `/api/v1/ops/reports/cash-daily/receipts?date=${encodeURIComponent(date)}&page=${page}`,
    "GET", token,
  );
  if (response.status !== 200) return { ok: false, status: response.status };
  const data = dataFromEnvelope(response.payload);
  return isPage(data, date, page)
    ? { ok: true, data }
    : { ok: false, status: 502 };
}
