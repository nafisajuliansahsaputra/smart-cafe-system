"use server";

import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { STAFF_COOKIE, staffApi, staffToken } from "@/lib/staff-server";

export async function changeOwnPassword(form:FormData):Promise<void> {
  const current=form.get("currentPassword"),next=form.get("newPassword"),
    confirmation=form.get("confirmation");
  const validNew=(value:FormDataEntryValue|null):value is string=>
    typeof value==="string" && value.length>=16 && value.length<=72 &&
    /^[\x21-\x7e]+$/.test(value);
  if(typeof current!=="string"||current.length<1||current.length>128||
    !validNew(next)||form.get("confirm")!=="yes")
    redirect("/staff/account?notice=invalid");
  if(next!==confirmation)redirect("/staff/account?notice=mismatch");
  const token=await staffToken();
  if(!token)redirect("/staff/login?status=expired");
  const result=await staffApi("/api/v1/staff/auth/change-password","POST",token,{
    currentPassword:current,newPassword:next,
  });
  if(result.status===200) {
    (await cookies()).set(STAFF_COOKIE,"",{
      httpOnly:true,secure:process.env.NODE_ENV==="production",
      sameSite:"strict",path:"/staff",maxAge:0,
    });
    redirect("/staff/login?status=password-changed");
  }
  if(result.status===401)redirect("/staff/account?notice=credentials");
  if(result.status===429)redirect("/staff/account?notice=limited");
  if(result.status===409)redirect("/staff/account?notice=conflict");
  redirect("/staff/account?notice="+
    (result.status===0||result.status>=500?"offline":"invalid"));
}
