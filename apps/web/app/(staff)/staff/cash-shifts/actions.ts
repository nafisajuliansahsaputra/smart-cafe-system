"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { staffApi, staffToken } from "@/lib/staff-server";

const KEY = /^[A-Za-z0-9_-]{8,128}$/;
const UUID = /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i;
function amount(input: FormDataEntryValue | null): number | null {
  if (typeof input !== "string" || !/^(0|[1-9][0-9]{0,12})$/.test(input)) return null;
  const value = Number(input);
  return Number.isSafeInteger(value) && value <= 1_000_000_000_000 ? value : null;
}
function fail(status: number): string {
  return status === 403 || status === 404 ? "forbidden" :
    status === 409 ? "conflict" :
      status === 0 || status >= 500 ? "offline" : "invalid";
}

export async function openCashShift(form: FormData): Promise<void> {
  const opening = amount(form.get("openingFloatIdr"));
  const key = form.get("idempotencyKey");
  if (opening === null || typeof key !== "string" || !KEY.test(key) ||
      form.get("confirm") !== "yes") {
    redirect("/staff/cash-shifts?notice=invalid");
  }
  const token = await staffToken();
  if (!token) redirect("/staff/login?status=expired");
  const result = await staffApi("/api/v1/ops/cash-shifts", "POST", token,
    { openingFloatIdr: opening }, key);
  if (result.status === 401) redirect("/staff/login?status=expired");
  if (result.status !== 200) redirect("/staff/cash-shifts?notice=" + fail(result.status));
  revalidatePath("/staff/cash-shifts");
  redirect("/staff/cash-shifts?notice=opened");
}

export async function closeCashShift(form: FormData): Promise<void> {
  const id = form.get("shiftId"), key = form.get("idempotencyKey");
  const counted = amount(form.get("countedCashIdr"));
  const reason = form.get("reason");
  if (typeof id !== "string" || !UUID.test(id) ||
      typeof key !== "string" || !KEY.test(key) || counted === null ||
      typeof reason !== "string" || reason.trim().length < 5 ||
      reason.trim().length > 300 || /[\x00-\x1f\x7f]/.test(reason) ||
      form.get("confirm") !== "yes") {
    redirect("/staff/cash-shifts?notice=invalid");
  }
  const token = await staffToken();
  if (!token) redirect("/staff/login?status=expired");
  const result = await staffApi(
    "/api/v1/ops/cash-shifts/" + id + "/close", "POST", token,
    { countedCashIdr: counted, reason: reason.trim() }, key);
  if (result.status === 401) redirect("/staff/login?status=expired");
  if (result.status !== 200) redirect("/staff/cash-shifts?notice=" + fail(result.status));
  revalidatePath("/staff/cash-shifts");
  redirect("/staff/cash-shifts?notice=closed");
}

/** Confirm physical transfer only after counting the waiter-held net cash. */
export async function receiveWaiterCash(form: FormData): Promise<void> {
  const id = form.get("shiftId");
  const tenderId = form.get("tenderId");
  const key = form.get("idempotencyKey");
  const counted = amount(form.get("countedNetIdr"));
  if (typeof id !== "string" || !UUID.test(id) ||
      typeof tenderId !== "string" || !UUID.test(tenderId) ||
      typeof key !== "string" || !KEY.test(key) || counted === null ||
      form.get("confirm") !== "yes") {
    redirect("/staff/cash-shifts?notice=invalid");
  }
  const token = await staffToken();
  if (!token) redirect("/staff/login?status=expired");
  const result = await staffApi(
    "/api/v1/ops/cash-shifts/" + id + "/handoffs/" + tenderId + "/receive",
    "POST", token, { countedNetIdr: counted, confirmedPhysicalReceipt: true }, key,
  );
  if (result.status === 401) redirect("/staff/login?status=expired");
  if (result.status !== 200) redirect("/staff/cash-shifts?notice=" + fail(result.status));
  revalidatePath("/staff/cash-shifts");
  redirect("/staff/cash-shifts?notice=received");
}
