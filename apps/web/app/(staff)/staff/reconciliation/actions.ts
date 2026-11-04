"use server";

import { createHash } from "node:crypto";
import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import {
  dataFromEnvelope, sandboxReviewClassifications, sandboxReviewEnabled,
  staffApi, staffIdentity, staffToken,
} from "@/lib/staff-server";

const UUID=/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i;
const CODES=["INVESTIGATE","REQUEST_PROVIDER_REVIEW","ESCALATE"] as const;

/** Records an investigation note. NEVER a payment approval or settlement. */
export async function recordSandboxTriage(form:FormData):Promise<void> {
  if(!sandboxReviewEnabled())redirect("/staff");
  const id=form.get("evidenceId");
  const code=form.get("code");
  const page=form.get("page");
  const filter=form.get("classification");
  if(typeof id!=="string"||!UUID.test(id)||
     typeof code!=="string"||!CODES.some(v=>v===code)||
     typeof page!=="string"||!/^(0|[1-9][0-9]{0,3})$/.test(page)||
     Number(page)>1000||typeof filter!=="string"||
     !sandboxReviewClassifications.some(v=>v===filter)||form.get("confirm")!=="yes") {
    redirect("/staff/reconciliation?notice=invalid");
  }
  const back="/staff/reconciliation?page="+page+
    "&classification="+encodeURIComponent(filter);
  const identity=await staffIdentity();
  if(identity.kind==="signed-out")redirect("/staff/login?status=expired");
  if(identity.kind==="unavailable")redirect(back+"&notice=offline");
  if(!identity.staff.roles.includes("ADMIN"))redirect("/staff");
  const token=await staffToken();
  if(!token)redirect("/staff/login?status=expired");
  // Stable across rerenders and retries, scoped to actor+evidence+action.
  // Never expose a random idempotency key that might rotate during SSE refresh.
  const key="triage_"+createHash("sha256")
    .update(identity.staff.id+"|"+id+"|"+code).digest("hex");
  const response=await staffApi(
    "/api/v1/admin/payments/sandbox-events/"+id+"/triage",
    "POST",token,{code},key);
  if(response.status===401)redirect("/staff/login?status=expired");
  if(response.status!==200) {
    const notice=response.status===403?"forbidden":
      response.status===409?"conflict":
      response.status===404?"missing":
      response.status===0||response.status>=500?"offline":"invalid";
    redirect(back+"&notice="+notice);
  }
  const evidence=dataFromEnvelope(response.payload);
  if(typeof evidence!=="object"||evidence===null||
     !("evidenceId" in evidence)||evidence.evidenceId!==id||
     !("code" in evidence)||evidence.code!==code) {
    redirect(back+"&notice=offline");
  }
  revalidatePath("/staff/reconciliation");
  redirect(back+"&notice=recorded");
}
