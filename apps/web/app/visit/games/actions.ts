"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { guestApi, guestToken, UUID_PATTERN } from "@/lib/guest-server";

export async function requestPhysicalGame(form: FormData) {
  const gameId = form.get("gameId");
  if (typeof gameId !== "string" || !UUID_PATTERN.test(gameId) ||
      form.get("confirm") !== "yes") {
    redirect("/visit/games?notice=invalid");
  }
  const token = await guestToken();
  if (!token) redirect("/visit");
  const response = await guestApi("/api/v1/me/game-loans", "POST", token, { gameId });
  if (response.status === 401) redirect("/visit");
  const notice = response.status === 200 ? "requested" :
    response.status === 409 ? "conflict" :
    response.status === 0 || response.status >= 500 ? "offline" : "invalid";
  if (response.status === 200) revalidatePath("/visit/games");
  redirect(`/visit/games?notice=${notice}`);
}

/** Request pickup, not automatic stock release or completed return. */
export async function requestReturnPhysicalGame(form: FormData) {
  const loanId = form.get("loanId");
  if (typeof loanId !== "string" || !UUID_PATTERN.test(loanId) ||
      form.get("confirm") !== "yes") redirect("/visit/games?notice=invalid");
  const token = await guestToken();
  if (!token) redirect("/visit");
  const result = await guestApi(`/api/v1/me/game-loans/${loanId}/request-return`, "POST", token);
  if (result.status === 401) redirect("/visit");
  const notice = result.status === 200 ? "return-requested" :
    result.status === 409 ? "conflict" :
    result.status === 0 || result.status >= 500 ? "offline" : "invalid";
  if (result.status === 200) revalidatePath("/visit/games");
  redirect(`/visit/games?notice=${notice}`);
}
