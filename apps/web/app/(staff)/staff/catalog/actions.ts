"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { staffApi, staffToken } from "@/lib/staff-server";

const UUID = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i;
const SLUG = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;

function field(form: FormData, key: string, max: number): string | null {
  const v = form.get(key);
  if (typeof v !== "string" || v.length > max || /[\x00-\x1f\x7f]/.test(v)) return null;
  return v.trim();
}
function description(form: FormData): string | null {
  const value = form.get("description");
  if (typeof value !== "string") return null;
  const clean = value.replace(/\r\n?/g, "\n").trim();
  return clean.length <= 500 && !/[\x00-\x09\x0b-\x1f\x7f]/.test(clean) ? clean : null;
}
function label(form: FormData, key: string, max: number): string | null {
  const v = field(form, key, max);
  return v && v.length <= max ? v : null;
}
function id(form: FormData, key: string): string | null {
  const v = field(form, key, 36);
  return v && UUID.test(v) ? v : null;
}
function int(form: FormData, key: string, min: number, max: number): number | null {
  const v = field(form, key, 22);
  if (v === null || !/^-?(?:0|[1-9]\d*)$/.test(v)) return null;
  const n = Number(v);
  return Number.isSafeInteger(n) && n >= min && n <= max ? n : null;
}
function yes(form: FormData, key: string) {
  return form.get(key) === "yes";
}
function checked(form: FormData, key: string) {
  return form.get(key) === "on";
}
function location(itemId?: string) {
  return itemId ? "/staff/catalog/" + itemId : "/staff/catalog";
}
function fail(target: string, code: string): never {
  redirect(target + "?notice=" + code);
}
async function commit(
  target: string, path: string, method: "POST" | "PATCH",
  body: Record<string, unknown>, message: string,
): Promise<never> {
  const token = await staffToken();
  if (!token) redirect("/staff/login?status=expired");
  const result = await staffApi(path, method, token, body);
  if (result.status === 401) redirect("/staff/login?status=expired");
  if (result.status !== 200 && result.status !== 201) {
    const error = result.status === 403 ? "forbidden" :
      result.status === 409 ? "conflict" :
      result.status === 404 ? "missing" :
      result.status === 0 || result.status >= 500 ? "offline" : "invalid";
    fail(target, error);
  }
  revalidatePath("/staff/catalog");
  revalidatePath(target);
  revalidatePath("/visit/menu");
  redirect(target + "?notice=" + message);
}

export async function createCategory(form: FormData): Promise<never> {
  const name = label(form, "name", 120);
  const slug = label(form, "slug", 120);
  if (!name || !slug || !SLUG.test(slug) || !yes(form, "confirm")) {
    fail(location(), "invalid");
  }
  return commit(location(), "/api/v1/admin/menu-categories", "POST",
    { name, slug }, "category-created");
}
export async function toggleCategory(form: FormData): Promise<never> {
  const categoryId = id(form, "categoryId");
  const intended = field(form, "active", 5);
  if (!categoryId || !["true", "false"].includes(intended ?? "") ||
      !yes(form, "confirm")) {
    fail(location(), "invalid");
  }
  return commit(location(), "/api/v1/admin/menu-categories/" + categoryId + "/active",
    "PATCH", { active: intended === "true" }, "category-updated");
}
export async function createItem(form: FormData): Promise<never> {
  const categoryId = id(form, "categoryId");
  const name = label(form, "name", 120);
  const priceIdr = int(form, "priceIdr", 0, 1_000_000_000);
  const itemDescription = description(form);
  if (!categoryId || !name || priceIdr === null || itemDescription === null ||
      !yes(form, "confirm")) {
    fail(location(), "invalid");
  }
  return commit(location(), "/api/v1/admin/menu-items", "POST",
    { categoryId, name, priceIdr, description: itemDescription }, "item-created");
}
export async function editItem(form: FormData): Promise<never> {
  const itemId = id(form, "itemId");
  if (!itemId) fail(location(), "invalid");
  const target = location(itemId);
  const categoryId = id(form, "categoryId");
  const name = label(form, "name", 120);
  const priceIdr = int(form, "priceIdr", 0, 1_000_000_000);
  const expectedRevision = int(form, "expectedRevision", 1, Number.MAX_SAFE_INTEGER);
  const availability = field(form, "availability", 10);
  const itemDescription = description(form);
  if (!categoryId || !name || priceIdr === null || expectedRevision === null ||
      itemDescription === null ||
      !["AVAILABLE", "SOLD_OUT", "HIDDEN"].includes(availability ?? "") ||
      !yes(form, "confirm")) fail(target, "invalid");
  return commit(target, "/api/v1/admin/menu-items/" + itemId, "PATCH",
    { categoryId, name, priceIdr, availability, expectedRevision, description: itemDescription }, "item-updated");
}
export async function updateStock(form: FormData): Promise<never> {
  const itemId = id(form, "itemId");
  if (!itemId) fail(location(), "invalid");
  const target = location(itemId);
  const expectedVersion = int(form, "expectedVersion", 0, Number.MAX_SAFE_INTEGER);
  const quantityMode = field(form, "quantityMode", 9);
  const limitedQuantity = quantityMode === "unlimited" ? null :
    int(form, "limitedQuantity", 0, 1_000_000_000);
  const reason = label(form, "reason", 300);
  if (expectedVersion === null || !["unlimited", "limited"].includes(quantityMode ?? "") ||
      limitedQuantity === null && quantityMode !== "unlimited" ||
      !reason || reason.length < 4 || !yes(form, "confirm")) {
    fail(target, "invalid");
  }
  return commit(target, "/api/v1/admin/menu-items/" + itemId + "/availability",
    "PATCH", { available: checked(form, "available"), limitedQuantity,
      expectedVersion, reason }, "stock-updated");
}
export async function createGroup(form: FormData): Promise<never> {
  const itemId = id(form, "itemId");
  if (!itemId) fail(location(), "invalid");
  const target = location(itemId);
  const name = label(form, "name", 80);
  if (!name || !yes(form, "confirm")) fail(target, "invalid");
  return commit(target, "/api/v1/admin/menu-items/" + itemId + "/variant-group",
    "POST", { name, required: checked(form, "required") }, "group-created");
}
export async function editGroup(form: FormData): Promise<never> {
  const itemId = id(form, "itemId");
  if (!itemId) fail(location(), "invalid");
  const target = location(itemId);
  const groupId = id(form, "groupId");
  const name = label(form, "name", 80);
  const expectedRevision = int(form, "expectedRevision", 1, Number.MAX_SAFE_INTEGER);
  if (!groupId || !name || expectedRevision === null || !yes(form, "confirm")) {
    fail(target, "invalid");
  }
  return commit(target, "/api/v1/admin/variant-groups/" + groupId, "PATCH",
    { name, required: checked(form, "required"), active: checked(form, "active"),
      expectedRevision }, "group-updated");
}
export async function createOption(form: FormData): Promise<never> {
  const itemId = id(form, "itemId");
  if (!itemId) fail(location(), "invalid");
  const target = location(itemId);
  const groupId = id(form, "groupId");
  const key = label(form, "key", 60);
  const optionLabel = label(form, "label", 80);
  const priceDeltaIdr = int(form, "priceDeltaIdr", -1_000_000_000, 1_000_000_000);
  if (!groupId || !key || !SLUG.test(key) || !optionLabel ||
      priceDeltaIdr === null || !yes(form, "confirm")) fail(target, "invalid");
  return commit(target, "/api/v1/admin/variant-groups/" + groupId + "/options",
    "POST", { key, label: optionLabel, priceDeltaIdr }, "option-created");
}
export async function editOption(form: FormData): Promise<never> {
  const itemId = id(form, "itemId");
  if (!itemId) fail(location(), "invalid");
  const target = location(itemId);
  const optionId = id(form, "optionId");
  const optionLabel = label(form, "label", 80);
  const priceDeltaIdr = int(form, "priceDeltaIdr", -1_000_000_000, 1_000_000_000);
  const expectedRevision = int(form, "expectedRevision", 1, Number.MAX_SAFE_INTEGER);
  if (!optionId || !optionLabel || priceDeltaIdr === null || expectedRevision === null ||
      !yes(form, "confirm")) fail(target, "invalid");
  return commit(target, "/api/v1/admin/variant-options/" + optionId, "PATCH",
    { label: optionLabel, priceDeltaIdr, active: checked(form, "active"),
      expectedRevision }, "option-updated");
}
