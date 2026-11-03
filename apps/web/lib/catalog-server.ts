import "server-only";

import { dataFromEnvelope, staffApi, staffToken } from "@/lib/staff-server";

export type MenuCategory = { id: string; name: string; slug: string; active: boolean };
export type MenuItem = {
  id: string; categoryId: string; name: string; priceIdr: number;
  availability: "AVAILABLE" | "SOLD_OUT" | "HIDDEN"; revision: number;
  description: string | null;
};
export type MenuStock = { itemId: string; available: boolean; limitedQuantity: number | null; version: number };
export type MenuOption = {
  id: string; key: string; label: string; priceDeltaIdr: number;
  active: boolean; revision: number;
};
export type MenuGroup = {
  id: string; itemId: string; name: string; required: boolean;
  active: boolean; revision: number; options: MenuOption[];
};
export type MenuItemPage = {
  page: number; pageSize: number; total: number; hasNext: boolean;
  items: MenuItem[];
};
export type CatalogResult<T> = { ok: true; data: T } | { ok: false; status: number };

const object = (v: unknown): v is Record<string, unknown> =>
  typeof v === "object" && v !== null && !Array.isArray(v);
const uuid = (v: unknown) => typeof v === "string" &&
  /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(v);
const number = (v: unknown): v is number => typeof v === "number" && Number.isSafeInteger(v);
const isCategory = (v: unknown): v is MenuCategory =>
  object(v) && uuid(v.id) && typeof v.name === "string" &&
  typeof v.slug === "string" && typeof v.active === "boolean";
const isItem = (v: unknown): v is MenuItem =>
  object(v) && uuid(v.id) && uuid(v.categoryId) && typeof v.name === "string" &&
  number(v.priceIdr) && number(v.revision) &&
  (v.description === null || typeof v.description === "string" &&
    v.description.length <= 500) &&
  ["AVAILABLE", "SOLD_OUT", "HIDDEN"].includes(String(v.availability));
const isStock = (v: unknown): v is MenuStock =>
  object(v) && uuid(v.itemId) && typeof v.available === "boolean" &&
  (v.limitedQuantity === null || number(v.limitedQuantity)) && number(v.version);
const isOption = (v: unknown): v is MenuOption =>
  object(v) && uuid(v.id) && typeof v.key === "string" &&
  typeof v.label === "string" && number(v.priceDeltaIdr) &&
  typeof v.active === "boolean" && number(v.revision);
const isGroup = (v: unknown): v is MenuGroup =>
  object(v) && uuid(v.id) && uuid(v.itemId) && typeof v.name === "string" &&
  typeof v.required === "boolean" && typeof v.active === "boolean" &&
  number(v.revision) && Array.isArray(v.options) && v.options.every(isOption);

async function fetchCatalog<T>(
  path: string, token: string, check: (value: unknown) => value is T,
): Promise<CatalogResult<T>> {
  const response = await staffApi(path, "GET", token);
  if (response.status !== 200) return { ok: false, status: response.status };
  const data = dataFromEnvelope(response.payload);
  return check(data) ? { ok: true, data } : { ok: false, status: 502 };
}

/** Item editor needs categories, never the legacy capped product list. */
export async function catalogCategories(): Promise<CatalogResult<MenuCategory[]>> {
  const token = await staffToken();
  if (!token) return { ok: false, status: 401 };
  return fetchCatalog("/api/v1/admin/menu-categories", token,
    (v): v is MenuCategory[] => Array.isArray(v) && v.every(isCategory));
}

export async function catalogOverview(): Promise<CatalogResult<{
  categories: MenuCategory[]; items: MenuItem[];
}>> {
  const token = await staffToken();
  if (!token) return { ok: false, status: 401 };
  const [categories, items] = await Promise.all([
    fetchCatalog("/api/v1/admin/menu-categories", token,
      (v): v is MenuCategory[] => Array.isArray(v) && v.every(isCategory)),
    fetchCatalog("/api/v1/admin/menu-items", token,
      (v): v is MenuItem[] => Array.isArray(v) && v.every(isItem)),
  ]);
  if (!categories.ok) return categories;
  if (!items.ok) return items;
  return { ok: true, data: { categories: categories.data, items: items.data } };
}

/** Manager inventory search runs in PostgreSQL, beyond the legacy 500-row cap. */
export async function catalogListing(page: number, q: string): Promise<CatalogResult<{
  categories: MenuCategory[]; listing: MenuItemPage;
}>> {
  if (!Number.isInteger(page) || page < 0 || page > 1000 ||
    q.length > 80 || /[\\x00-\\x1f\\x7f]/.test(q)) {
    return { ok: false, status: 400 };
  }
  const token = await staffToken();
  if (!token) return { ok: false, status: 401 };
  const params = new URLSearchParams({ page: String(page), q });
  const validPage = (value: unknown): value is MenuItemPage =>
    object(value) && value.page === page && value.pageSize === 20 &&
    number(value.total) && value.total >= 0 &&
    typeof value.hasNext === "boolean" &&
    Array.isArray(value.items) && value.items.length <= 20 &&
    value.items.every(isItem) &&
    !(value.items.length === 0 && value.hasNext) &&
    value.items.length <= value.total;
  const [categories, listing] = await Promise.all([
    fetchCatalog("/api/v1/admin/menu-categories", token,
      (v): v is MenuCategory[] => Array.isArray(v) && v.every(isCategory)),
    fetchCatalog("/api/v1/admin/menu-items/page?" + params.toString(), token, validPage),
  ]);
  if (!categories.ok) return categories;
  if (!listing.ok) return listing;
  return { ok: true, data: { categories: categories.data, listing: listing.data } };
}

export async function catalogItemDetail(id: string): Promise<CatalogResult<{
  item: MenuItem; stock: MenuStock; groups: MenuGroup[];
}>> {
  if (!uuid(id)) return { ok: false, status: 404 };
  const token = await staffToken();
  if (!token) return { ok: false, status: 401 };
  const safeId = encodeURIComponent(id);
  const [item, stock, groups] = await Promise.all([
    fetchCatalog("/api/v1/admin/menu-items/" + safeId, token, isItem),
    fetchCatalog("/api/v1/admin/menu-items/" + safeId + "/availability", token, isStock),
    fetchCatalog("/api/v1/admin/menu-items/" + safeId + "/variant-groups", token,
      (v): v is MenuGroup[] => Array.isArray(v) && v.every(isGroup)),
  ]);
  if (!item.ok) return item;
  if (!stock.ok) return stock;
  if (!groups.ok) return groups;
  if (item.data.id !== id || stock.data.itemId !== id ||
      groups.data.some((group) => group.itemId !== id)) {
    return { ok: false, status: 502 };
  }
  return { ok: true, data: { item: item.data, stock: stock.data, groups: groups.data } };
}
