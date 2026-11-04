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


/**
 * ADMIN-only server action. No password in URL, cookies, server logs or return.
 * A new account remains disabled until a separate authorization step.
 */
export async function createStaffAccount(form:FormData) {
  const login=form.get("login"),password=form.get("password"),
    role=form.get("role"),reason=form.get("reason"),confirm=form.get("confirm");
  if(typeof login!=="string"||!/^[A-Za-z][A-Za-z0-9._-]{2,39}$/.test(login)||
    typeof password!=="string"||password.length<16||password.length>72||
    /[^\x21-\x7e]/.test(password)||
    !["WAITER","KITCHEN","CASHIER"].includes(String(role))||
    typeof reason!=="string"||reason.trim().length<5||reason.trim().length>300||
    /[\x00-\x1f\x7f]/.test(reason)||
    confirm!=="yes")redirect("/staff/team?notice=invalid");
  const token=await staffToken();
  if(!token)redirect("/staff/login?status=expired");
  const result=await staffApi("/api/v1/admin/staff","POST",token,{
    login:login.toLowerCase(),password,role,reason:reason.trim(),
  });
  if(result.status===401)redirect("/staff/login?status=expired");
  if(result.status!==201) {
    const notice=result.status===403?"forbidden":
      result.status===409?"duplicate":
      result.status===0||result.status>=500?"offline":"invalid";
    redirect("/staff/team?notice="+notice);
  }
  revalidatePath("/staff/team");
  redirect("/staff/team?notice=created");
}
