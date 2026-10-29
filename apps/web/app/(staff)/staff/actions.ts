"use server";

import { cookies } from "next/headers";
import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import {
  dataFromEnvelope,
  STAFF_COOKIE,
  staffApi,
  staffToken,
} from "@/lib/staff-server";

const ORDER_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export async function loginStaff(form: FormData): Promise<void> {
  const login = form.get("login");
  const password = form.get("password");
  if (
    typeof login !== "string" ||
    !login.trim() ||
    login.length > 120 ||
    typeof password !== "string" ||
    password.length < 8 ||
    password.length > 128
  ) {
    redirect("/staff/login?status=invalid");
  }
  const result = await staffApi("/api/v1/staff/auth/login", "POST", undefined, {
    login: login.trim(),
    password,
  });
  if (result.status !== 200) {
    redirect(
      result.status === 429
        ? "/staff/login?status=limited"
        : result.status === 0 || result.status >= 500
          ? "/staff/login?status=offline"
          : "/staff/login?status=invalid",
    );
  }
  const data = dataFromEnvelope(result.payload);
  if (
    typeof data !== "object" ||
    data === null ||
    !("accessToken" in data) ||
    typeof data.accessToken !== "string" ||
    !/^[A-Za-z0-9_-]{43}$/.test(data.accessToken) ||
    !("expiresAt" in data) ||
    typeof data.expiresAt !== "string"
  ) {
    redirect("/staff/login?status=offline");
  }
  const remaining = Math.floor((Date.parse(data.expiresAt) - Date.now()) / 1000);
  if (!Number.isFinite(remaining) || remaining <= 0) {
    redirect("/staff/login?status=offline");
  }
  (await cookies()).set(STAFF_COOKIE, data.accessToken, {
    httpOnly: true,
    secure: process.env.NODE_ENV === "production",
    sameSite: "strict",
    path: "/staff",
    maxAge: Math.min(28800, remaining),
  });
  redirect("/staff");
}

async function clearStaffCookie(): Promise<void> {
  (await cookies()).set(STAFF_COOKIE, "", {
    httpOnly: true,
    secure: process.env.NODE_ENV === "production",
    sameSite: "strict",
    path: "/staff",
    maxAge: 0,
  });
}

export async function logoutStaff(): Promise<void> {
  const token = await staffToken();
  const result = token
    ? await staffApi("/api/v1/staff/auth/logout", "POST", token)
    : { status: 401 };
  await clearStaffCookie();
  redirect(
    result.status === 200 || result.status === 401
      ? "/staff/login?status=signed-out"
      : "/staff/login?status=revocation-unconfirmed",
  );
}

async function command(form: FormData, kind: "approve" | "reject"): Promise<void> {
  const id = form.get("orderId");
  if (typeof id !== "string" || !ORDER_ID.test(id)) {
    redirect("/staff/orders?notice=invalid");
  }
  const reason = form.get("reason");
  if (
    kind === "reject" &&
    (typeof reason !== "string" || reason.trim().length < 4 || reason.length > 300)
  ) {
    redirect(`/staff/orders/${id}?notice=reason`);
  }
  const token = await staffToken();
  if (!token) redirect("/staff/login?status=expired");
  const result = await staffApi(
    `/api/v1/ops/orders/${id}/${kind === "approve" ? "approve-cash" : "reject"}`,
    "POST",
    token,
    kind === "reject" ? { reason: (reason as string).trim() } : undefined,
  );
  if (result.status === 401) {
    await clearStaffCookie();
    redirect("/staff/login?status=expired");
  }
  if (result.status !== 200) {
    const notice =
      result.status === 403
        ? "forbidden"
        : result.status === 409
          ? "conflict"
          : result.status === 0 || result.status >= 500
            ? "offline"
            : "failed";
    redirect(`/staff/orders/${id}?notice=${notice}`);
  }
  revalidatePath("/staff/orders");
  revalidatePath(`/staff/orders/${id}`);
  redirect(`/staff/orders/${id}?notice=${kind === "approve" ? "approved" : "rejected"}`);
}

export async function approveCash(form: FormData): Promise<void> {
  await command(form, "approve");
}

export async function rejectCash(form: FormData): Promise<void> {
  await command(form, "reject");
}
