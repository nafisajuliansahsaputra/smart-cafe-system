"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { dataFromEnvelope, staffApi, staffToken } from "@/lib/staff-server";

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const KEY_PATTERN = /^[a-zA-Z0-9_-]{8,128}$/;

function commandFailure(status: number): string {
  if (status === 401) return "expired";
  if (status === 403) return "forbidden";
  if (status === 409) return "conflict";
  if (status === 0 || status >= 500) return "offline";
  return "failed";
}

async function submit(path: string, destination: string, body?: Record<string, unknown>, key?: string) {
  const token = await staffToken();
  if (!token) redirect("/staff/login?status=expired");
  const result = await staffApi(path, "POST", token, body, key);
  if (result.status === 401) redirect("/staff/login?status=expired");
  if (result.status !== 200) redirect(`${destination}?notice=${commandFailure(result.status)}`);
  revalidatePath(destination);
  redirect(`${destination}?notice=updated`);
}

async function kitchenAction(form: FormData, operation: "start" | "ready") {
  const id = form.get("ticketId");
  if (typeof id !== "string" || !UUID_PATTERN.test(id) || form.get("confirm") !== "yes") {
    redirect("/staff/kitchen?notice=invalid");
  }
  await submit(`/api/v1/kitchen/tickets/${id}/${operation}`, "/staff/kitchen");
}

export async function startKitchenTicket(form: FormData) {
  await kitchenAction(form, "start");
}

export async function markKitchenReady(form: FormData) {
  await kitchenAction(form, "ready");
}

export async function markOrderServed(form: FormData) {
  const id = form.get("orderId");
  if (typeof id !== "string" || !UUID_PATTERN.test(id) || form.get("confirm") !== "yes") {
    redirect("/staff/waiter?notice=invalid");
  }
  await submit(`/api/v1/waiter/orders/${id}/served`, "/staff/waiter");
}

export async function captureCashTender(form: FormData) {
  const intent = form.get("paymentIntentId");
  const receivedRaw = form.get("amountReceivedIdr");
  const key = form.get("idempotencyKey");
  if (
    typeof intent !== "string" ||
    !UUID_PATTERN.test(intent) ||
    typeof receivedRaw !== "string" ||
    !/^[1-9][0-9]{0,11}$/.test(receivedRaw) ||
    typeof key !== "string" ||
    !KEY_PATTERN.test(key) ||
    form.get("confirm") !== "yes"
  ) {
    redirect("/staff/waiter?notice=invalid");
  }
  const amount = Number(receivedRaw);
  if (!Number.isSafeInteger(amount) || amount <= 0) {
    redirect("/staff/waiter?notice=invalid");
  }
  const token = await staffToken();
  if (!token) redirect("/staff/login?status=expired");
  const response = await staffApi(
    `/api/v1/ops/payments/${intent}/cash-tenders`,
    "POST", token, { amountReceivedIdr: amount }, key,
  );
  if (response.status === 401) redirect("/staff/login?status=expired");
  if (response.status !== 200) {
    redirect(`/staff/waiter?notice=${commandFailure(response.status)}`);
  }
  const result = dataFromEnvelope(response.payload);
  if (
    typeof result !== "object" || result === null ||
    !("paymentStatus" in result) || result.paymentStatus !== "PAID" ||
    !("paymentIntentId" in result) || result.paymentIntentId !== intent
  ) {
    redirect("/staff/waiter?notice=offline");
  }
  revalidatePath("/staff/waiter");
  revalidatePath("/staff/orders");
  redirect("/staff/waiter?notice=updated");
}
