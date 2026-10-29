"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { staffApi, staffToken } from "@/lib/staff-server";

// Shared canonical UUID shape: 8-4-4-4-12. A missing segment rejected real loan IDs.
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const CODE = /^[A-Za-z0-9_-]{1,80}$/;
function error(status: number): string {
  if (status === 409) return "conflict";
  if (status === 403) return "forbidden";
  if (status === 0 || status >= 500) return "offline";
  return "invalid";
}
async function send(path: string, body?: Record<string,unknown>) {
  const token = await staffToken();
  if (!token) redirect("/staff/login?status=expired");
  const r = await staffApi(path,"POST",token,body);
  if (r.status===401) redirect("/staff/login?status=expired");
  if (r.status!==200) redirect(`/staff/games?notice=${error(r.status)}`);
  revalidatePath("/staff/games");
  redirect("/staff/games?notice=updated");
}
export async function updateGameLoan(form: FormData) {
  const id = form.get("loanId"), action = form.get("operation");
  if (typeof id!=="string" || !UUID.test(id) ||
      !["assign","handover","return","return-damaged","cancel"].includes(String(action)) ||
      form.get("confirm")!=="yes") redirect("/staff/games?notice=invalid");
  const op=String(action);
  if (op==="handover" || op==="return" || op==="return-damaged") {
    const code=form.get("copyCode");
    if (typeof code!=="string" || !CODE.test(code))
      redirect("/staff/games?notice=invalid");
    if (op==="return-damaged") {
      const reason=form.get("reason");
      if (typeof reason!=="string" || reason.trim().length<5 ||
          reason.trim().length>300 || /[\x00-\x1f\x7f]/.test(reason))
        redirect("/staff/games?notice=invalid");
      await send(`/api/v1/waiter/game-loans/${id}/return-damaged`,
        {copyCode:code,reason:reason.trim()});
    }
    await send(`/api/v1/waiter/game-loans/${id}/${op}`,{copyCode:code});
  }
  await send(`/api/v1/waiter/game-loans/${id}/${op}`);
}
/** A report is not a physical return and never releases any copy. */
export async function reportMissingGame(form: FormData) {
  const id=form.get("loanId"),reason=form.get("reason");
  if (typeof id!=="string" || !UUID.test(id) ||
      typeof reason!=="string" || reason.trim().length<5 ||
      reason.trim().length>300 || /[\x00-\x1f\x7f]/.test(reason) ||
      form.get("confirm")!=="yes") redirect("/staff/games?notice=invalid");
  await send(`/api/v1/waiter/game-loans/${id}/report-loss`,{reason:reason.trim()});
}
/** Review resolution needs a manager session and matching unit code for loss. */
export async function reviewMissingGame(form: FormData) {
  const id=form.get("incidentId"),decision=form.get("decision");
  const code=form.get("copyCode"),reason=form.get("reason");
  if (typeof id!=="string" || !UUID.test(id) ||
      (decision!=="DISMISS"&&decision!=="CONFIRM_LOST") ||
      typeof reason!=="string" || reason.trim().length<5 ||
      reason.trim().length>300 || /[\x00-\x1f\x7f]/.test(reason) ||
      (decision==="CONFIRM_LOST" && (typeof code!=="string" || !CODE.test(code))) ||
      form.get("confirm")!=="yes") redirect("/staff/games?notice=invalid");
  await send(`/api/v1/admin/games/loss-incidents/${id}/review`,
    {decision,reason:reason.trim(),copyCode:decision==="CONFIRM_LOST"?code:null});
}
export async function createPhysicalGame(form: FormData) {
  const name=form.get("name"), desc=form.get("description");
  if (typeof name!=="string" || !name.trim() || name.trim().length>120 ||
    typeof desc!=="string" || desc.length>500)
    redirect("/staff/games?notice=invalid");
  await send("/api/v1/admin/games",{name:name.trim(),description:desc.trim()});
}
export async function registerGameCopy(form: FormData) {
  const id=form.get("gameId"), code=form.get("copyCode");
  if (typeof id!=="string" || !UUID.test(id) ||
    typeof code!=="string" || !CODE.test(code))
    redirect("/staff/games?notice=invalid");
  await send(`/api/v1/admin/games/${id}/copies`,{copyCode:code});
}

/** Only manager/admin may change physical-copy condition. */
export async function updateGameCopyCondition(form: FormData) {
  const id=form.get("copyId"), condition=form.get("condition"), reason=form.get("reason");
  if (typeof id!=="string" || !UUID.test(id) ||
      (condition!=="DAMAGED" && condition!=="AVAILABLE") ||
      typeof reason!=="string" || reason.trim().length<5 ||
      reason.trim().length>300 || /[\x00-\x1f\x7f]/.test(reason) ||
      form.get("confirm")!=="yes") redirect("/staff/games?notice=invalid");
  await send(`/api/v1/admin/games/copies/${id}/condition`,
    {condition,reason:reason.trim()});
}
