"use server";

import { cookies } from "next/headers";
import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import {
  GUEST_COOKIE, TABLE_ALIAS, UUID_PATTERN, envelopeData, getCart, getVariants,
  guestApi, guestToken, isCart, isRecord,
} from "@/lib/guest-server";

function notice(status: number): string {
  if (status === 401 || status === 403) return "expired";
  if (status === 409) return "conflict";
  if (status === 404) return "missing";
  if (status === 400) return "invalid";
  if (status === 0 || status >= 500) return "offline";
  return "failed";
}
function toVisitDestination(status: number): string {
  return status === 401 || status === 403
    ? "/visit?notice=expired" : `/visit/cart?notice=${notice(status)}`;
}
function clearVisitCookie() {
  return cookies().then((jar) =>
    jar.set(GUEST_COOKIE, "", {
      httpOnly: true, sameSite: "strict", secure: process.env.NODE_ENV === "production",
      path: "/visit", maxAge: 0,
    }));
}

export async function joinTable(form: FormData): Promise<void> {
  const alias = form.get("alias");
  const displayName = form.get("displayName");
  const joinProof = form.get("joinProof");
  if (typeof alias !== "string" || !TABLE_ALIAS.test(alias)) {
    redirect("/");
  }
  const back = `/t/${alias}`;
  if (
    typeof displayName !== "string" || displayName.trim().length < 1 ||
    displayName.trim().length > 80 || typeof joinProof !== "string" ||
    !/^[A-Za-z0-9_-]{43}$/.test(joinProof)
  ) {
    redirect(`${back}?notice=invalid`);
  }
  const response = await guestApi("/api/v1/table-visits/join", "POST", undefined, {
    tableAlias: alias, displayName: displayName.trim(), joinProof,
  });
  if (response.status !== 200) {
    redirect(`${back}?notice=${notice(response.status)}`);
  }
  const data = envelopeData(response.payload);
  if (!isRecord(data) || !UUID_PATTERN.test(String(data.guestId)) ||
      !UUID_PATTERN.test(String(data.visitId)) ||
      typeof data.accessToken !== "string" ||
      !/^[A-Za-z0-9_-]{43}$/.test(data.accessToken) ||
      typeof data.expiresAt !== "string") {
    redirect(`${back}?notice=offline`);
  }
  const maxAge = Math.floor((Date.parse(data.expiresAt) - Date.now()) / 1000);
  if (!Number.isFinite(maxAge) || maxAge <= 0) {
    redirect(`${back}?notice=offline`);
  }
  (await cookies()).set(GUEST_COOKIE, data.accessToken, {
    httpOnly: true, secure: process.env.NODE_ENV === "production",
    sameSite: "strict", path: "/visit", maxAge: Math.min(maxAge, 28800),
  });
  redirect("/visit");
}

export async function exitVisit(): Promise<void> {
  const token = await guestToken();
  const response = token
    ? await guestApi("/api/v1/me/logout", "POST", token)
    : { status: 401 };
  await clearVisitCookie();
  // Never claim server revocation if the API was unreachable.
  redirect(
    response.status === 200 || response.status === 401
      ? "/?notice=left" : "/?notice=revocation-unconfirmed",
  );
}

async function updateCart(lines: { menuItemId: string; quantity: number; variantOptionIds: string[] }[],
                          expectedVersion: number, destination: string): Promise<never> {
  const token = await guestToken();
  if (!token) redirect("/visit?notice=expired");
  const result = await guestApi("/api/v1/me/cart", "PUT", token, { expectedVersion, lines });
  if (result.status !== 200 || !isCart(envelopeData(result.payload))) {
    redirect(`${destination}?notice=${result.status === 200 ? "offline" : notice(result.status)}`);
  }
  revalidatePath("/visit");
  revalidatePath("/visit/cart");
  redirect(`${destination}?notice=updated`);
}

export async function addCartItem(form: FormData): Promise<void> {
  const id = form.get("itemId");
  const rawQuantity = form.get("quantity");
  if (typeof id !== "string" || !UUID_PATTERN.test(id) ||
      typeof rawQuantity !== "string" || !/^(?:[1-9]|1[0-9]|20)$/.test(rawQuantity)) {
    redirect("/visit?notice=invalid");
  }
  const itemDestination = `/visit/menu/${id}`;
  const groups = await getVariants(id);
  if (!groups.ok) redirect(`${itemDestination}?notice=${notice(groups.status)}`);
  const selections: string[] = [];
  for (const group of groups.data) {
    const option = form.get(`group:${group.id}`);
    if (option === null || option === "") {
      if (group.required) redirect(`${itemDestination}?notice=invalid`);
      continue;
    }
    if (typeof option !== "string" ||
        !group.options.some((o) => o.id === option)) {
      redirect(`${itemDestination}?notice=invalid`);
    }
    selections.push(option);
  }
  const cart = await getCart();
  if (!cart.ok) redirect(`${itemDestination}?notice=${notice(cart.status)}`);
  const remaining = cart.data.lines
    .filter((line) => line.menuItemId !== id)
    .map((line) => ({ menuItemId: line.menuItemId, quantity: line.quantity,
      variantOptionIds: line.variantOptionIds }));
  if (remaining.length >= 30) redirect(`${itemDestination}?notice=invalid`);
  remaining.push({ menuItemId: id, quantity: Number(rawQuantity), variantOptionIds: selections });
  await updateCart(remaining, cart.data.version, "/visit/cart");
}

export async function updateCartItem(form: FormData): Promise<void> {
  const id = form.get("itemId");
  const quantity = form.get("quantity");
  if (typeof id !== "string" || !UUID_PATTERN.test(id) ||
      typeof quantity !== "string" || !/^(?:0|[1-9]|1[0-9]|20)$/.test(quantity)) {
    redirect("/visit/cart?notice=invalid");
  }
  const cart = await getCart();
  if (!cart.ok) redirect(toVisitDestination(cart.status));
  if (!cart.data.lines.some((line) => line.menuItemId === id)) {
    redirect("/visit/cart?notice=conflict");
  }
  const next = cart.data.lines.filter((line) => line.menuItemId !== id).map((line) => ({
    menuItemId: line.menuItemId, quantity: line.quantity,
    variantOptionIds: line.variantOptionIds,
  }));
  if (quantity !== "0") {
    const old = cart.data.lines.find((line) => line.menuItemId === id)!;
    next.push({ menuItemId: id, quantity: Number(quantity), variantOptionIds: old.variantOptionIds });
  }
  await updateCart(next, cart.data.version, "/visit/cart");
}

export async function checkoutCash(form: FormData): Promise<void> {
  const version = form.get("cartVersion");
  const key = form.get("idempotencyKey");
  if (form.get("confirm") !== "yes" ||
      typeof version !== "string" || !/^(?:0|[1-9][0-9]{0,12})$/.test(version) ||
      typeof key !== "string" || !/^[a-zA-Z0-9_-]{8,128}$/.test(key)) {
    redirect("/visit/cart?notice=invalid");
  }
  const token = await guestToken();
  if (!token) redirect("/visit?notice=expired");
  const response = await guestApi("/api/v1/orders", "POST", token,
    { cartVersion: Number(version) }, key);
  if (response.status !== 200) {
    redirect(`/visit/cart?notice=${notice(response.status)}`);
  }
  const order = envelopeData(response.payload);
  if (!isRecord(order) || typeof order.id !== "string" ||
      !UUID_PATTERN.test(order.id)) {
    redirect("/visit/cart?notice=offline");
  }
  revalidatePath("/visit/cart");
  revalidatePath("/visit/orders");
  redirect(`/visit/orders/${order.id}?notice=submitted`);
}

export async function restoreVisit(form: FormData): Promise<void> {
  // A stale session cannot be repaired without a new staff-issued join proof.
  const alias = form.get("alias");
  if (typeof alias !== "string" || !TABLE_ALIAS.test(alias)) redirect("/");
  await clearVisitCookie();
  redirect(`/t/${alias}`);
}

