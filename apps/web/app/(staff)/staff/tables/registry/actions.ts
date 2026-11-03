"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { staffApi, staffToken } from "@/lib/staff-server";

const UUID = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i;
function field(form: FormData, key: string, max: number): string | null {
  const value = form.get(key);
  if (typeof value !== "string" || value.length > max ||
      /[\x00-\x1f\x7f]/.test(value)) return null;
  return value.trim();
}
function capacity(form: FormData, name: string): number | null {
  const value = field(form, name, 2);
  if (!value || !/^(?:[1-9]|[12][0-9]|30)$/.test(value)) return null;
  return Number(value);
}
function fail(code: string): never {
  redirect("/staff/tables/registry?notice=" + code);
}
async function submit(path: string, method: "POST" | "PATCH",
                      body: Record<string, unknown>, notice: string): Promise<never> {
  const token = await staffToken();
  if (!token) redirect("/staff/login?status=expired");
  const result = await staffApi(path, method, token, body);
  if (result.status === 401) redirect("/staff/login?status=expired");
  if (result.status !== 200 && result.status !== 201) {
    fail(result.status === 403 ? "forbidden" :
      result.status === 409 ? "conflict" :
      result.status === 404 ? "missing" :
      result.status === 0 || result.status >= 500 ? "offline" : "invalid");
  }
  revalidatePath("/staff/tables/registry");
  revalidatePath("/staff/tables");
  redirect("/staff/tables/registry?notice=" + notice);
}
export async function createTable(form: FormData): Promise<never> {
  const rawCode = field(form, "tableCode", 40);
  const tableCode = rawCode?.toUpperCase() ?? "";
  const publicAlias = field(form, "publicAlias", 100);
  const amount = capacity(form, "capacity");
  if (!/^[A-Z0-9][A-Z0-9_-]{0,39}$/.test(tableCode) ||
      !publicAlias || !/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(publicAlias) ||
      amount === null || form.get("confirm") !== "yes") fail("invalid");
  return submit("/api/v1/admin/tables", "POST",
    { tableCode, publicAlias, capacity: amount }, "created");
}
export async function editTable(form: FormData): Promise<never> {
  const tableId = field(form, "tableId", 36);
  const status = field(form, "status", 20);
  const expectedStatus = field(form, "expectedStatus", 20);
  const amount = capacity(form, "capacity");
  const expectedCapacity = capacity(form, "expectedCapacity");
  if (!tableId || !UUID.test(tableId) ||
      !["AVAILABLE", "OUT_OF_SERVICE"].includes(status ?? "") ||
      !["AVAILABLE", "OUT_OF_SERVICE"].includes(expectedStatus ?? "") ||
      amount === null || expectedCapacity === null ||
      form.get("confirm") !== "yes") fail("invalid");
  return submit("/api/v1/admin/tables/" + tableId, "PATCH",
    { status, capacity: amount, expectedStatus, expectedCapacity }, "updated");
}
