"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { dataFromEnvelope, staffApi, staffIdentity, staffToken } from "@/lib/staff-server";

function fail(code: string): never {
  redirect("/staff/table-settings?notice=" + code);
}
function textValue(form: FormData, key: string, max: number): string | null {
  const raw = form.get(key);
  if (typeof raw !== "string" || raw.length > max ||
    /[\x00-\x1f\x7f]/.test(raw)) return null;
  return raw.trim();
}
function capacity(form: FormData): number | null {
  const raw = textValue(form, "capacity", 4);
  if (!raw || !/^[1-9][0-9]*$/.test(raw)) return null;
  const value = Number(raw);
  return Number.isSafeInteger(value) && value >= 1 && value <= 100 ? value : null;
}
async function commit(path: string, method: "POST" | "PATCH",
  body: Record<string, unknown>, success: string): Promise<never> {
  const identity = await staffIdentity();
  if (identity.kind === "signed-out") redirect("/staff/login?status=expired");
  if (identity.kind !== "signed-in") fail("offline");
  if (!identity.staff.roles.some(r => r === "MANAGER" || r === "ADMIN")) fail("forbidden");
  const token = await staffToken();
  if (!token) redirect("/staff/login?status=expired");
  const response = await staffApi(path, method, token, body);
  if (response.status === 401) redirect("/staff/login?status=expired");
  if (response.status !== 200 && response.status !== 201) {
    fail(response.status === 403 ? "forbidden" :
      response.status === 409 ? "conflict" :
      response.status === 404 ? "missing" :
      response.status === 0 || response.status >= 500 ? "offline" : "invalid");
  }
  const result = dataFromEnvelope(response.payload);
  if (!result || typeof result !== "object") fail("unconfirmed");
  revalidatePath("/staff/table-settings");
  revalidatePath("/staff/tables");
  redirect("/staff/table-settings?notice=" + success);
}

export async function createCafeTable(form: FormData): Promise<never> {
  const tableCode = textValue(form, "tableCode", 40);
  const publicAlias = textValue(form, "publicAlias", 100);
  const count = capacity(form);
  if (!tableCode || !/^[A-Z0-9][A-Z0-9-]{0,39}$/.test(tableCode) ||
      !publicAlias || !/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(publicAlias) ||
      count === null || form.get("confirm") !== "yes") fail("invalid");
  return commit("/api/v1/admin/tables", "POST",
    { tableCode, publicAlias, capacity: count }, "created");
}
export async function updateCafeTable(form: FormData): Promise<never> {
  const id = textValue(form, "tableId", 36);
  const count = capacity(form);
  const status = textValue(form, "status", 20);
  if (!id || !/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i.test(id) ||
      count === null || !["AVAILABLE", "OUT_OF_SERVICE"].includes(status ?? "") ||
      form.get("confirm") !== "yes") fail("invalid");
  return commit("/api/v1/admin/tables/" + id, "PATCH",
    { capacity: count, status }, "updated");
}
