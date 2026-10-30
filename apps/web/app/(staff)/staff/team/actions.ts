"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { staffApi, staffToken } from "@/lib/staff-server";

const UUID=/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i;

export async function setStaffEnabled(form:FormData) {
  const id=form.get("staffId"),expected=form.get("expectedEnabled");
  const reason=form.get("reason"),confirm=form.get("confirm");
  if(typeof id!=="string"||!UUID.test(id)||
     (expected!=="true" && expected!=="false") ||
     typeof reason!=="string"||reason.trim().length<5||
     reason.trim().length>300||/[\x00-\x1f\x7f]/.test(reason)||
     confirm!=="yes") redirect("/staff/team?notice=invalid");
  const token=await staffToken();
  if(!token) redirect("/staff/login?status=expired");
  const result=await staffApi("/api/v1/admin/staff/"+id+"/status","POST",token,{
    enabled:expected!=="true",
    expectedEnabled:expected==="true",
    reason:reason.trim(),
  });
  if(result.status===401)redirect("/staff/login?status=expired");
  if(result.status!==200) {
    const notice=result.status===403?"forbidden":
      result.status===409?"conflict":
      result.status===0||result.status>=500?"offline":"invalid";
    redirect("/staff/team?notice="+notice);
  }
  revalidatePath("/staff/team");
  redirect("/staff/team?notice=updated");
}
