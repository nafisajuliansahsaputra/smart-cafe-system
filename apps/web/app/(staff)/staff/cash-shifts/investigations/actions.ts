"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { staffApi, staffToken } from "@/lib/staff-server";

const UUID = /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i;
const KEY = /^[A-Za-z0-9_-]{8,128}$/;
const ACTIONS = ["REQUEST_RECOUNT","DOCUMENT_EVIDENCE","ESCALATE"];

export async function addVarianceReview(form: FormData): Promise<void> {
  const id=form.get("shiftId"),actionCode=form.get("actionCode");
  const note=form.get("note"),key=form.get("idempotencyKey");
  const base="/staff/cash-shifts/investigations";
  if (typeof id!=="string" || !UUID.test(id) ||
      typeof actionCode!=="string" || !ACTIONS.includes(actionCode) ||
      typeof note!=="string" || note.trim().length<10 || note.trim().length>300 ||
      /[\x00-\x1f\x7f]/.test(note) ||
      typeof key!=="string" || !KEY.test(key) || form.get("confirm")!=="yes") {
    redirect(base+"?notice=invalid");
  }
  const token=await staffToken();
  if (!token) redirect("/staff/login?status=expired");
  const response=await staffApi(
    "/api/v1/ops/cash-variance-reviews/"+id+"/actions", "POST",token,
    {actionCode,note:note.trim()},key);
  if (response.status===401) redirect("/staff/login?status=expired");
  if (response.status!==200) {
    const notice=response.status===403 ? "forbidden" :
      response.status===409 ? "conflict" :
      response.status===404 ? "missing" :
      response.status===0 || response.status>=500 ? "offline" : "invalid";
    redirect(base+"?notice="+notice);
  }
  revalidatePath(base);
  redirect(base+"?notice=recorded&shift="+id);
}
