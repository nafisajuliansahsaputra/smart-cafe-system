import { NextResponse } from "next/server";
import { headers } from "next/headers";
import { guestApi } from "@/lib/guest-server";
import { staffApi } from "@/lib/staff-server";
import { fetchSystemStatus } from "@/lib/server-status";

export const dynamic = "force-dynamic";
export const maxDuration = 60;

/**
 * Preview-only read-only acceptance. Verify trusted project identity, live
 * Aiven connectivity and Spring Security's anonymous 401 responses without
 * mutating any staff, order, cash or payment data.
 */
export async function GET() {
  if (process.env.VERCEL_ENV !== "preview") {
    return new Response(null, { status: 404 });
  }

  const workloadIdentityPresent = (await headers()).has("x-vercel-oidc-token");
  const system = await fetchSystemStatus();

  if (!system) {
    return NextResponse.json({
      check: "smart-cafe-review-preview-connectivity",
      result: "not-ready",
      databaseConnected: false,
      workloadIdentityPresent,
      note: "Read-only synthetic preview check, no real payment or personal data.",
    }, { status: 503, headers: { "Cache-Control": "no-store" } });
  }

  const [table, menu, staff, ops] = await Promise.all([
    guestApi("/api/v1/tables/UNCONFIGURED-REVIEW-SMOKE/entry", "GET"),
    guestApi("/api/v1/menu", "GET"),
    staffApi("/api/v1/staff/auth/me", "GET"),
    staffApi("/api/v1/ops/orders", "GET"),
  ]);

  const passed = workloadIdentityPresent
    && system.database === "connected"
    && table.status === 404
    && menu.status === 200
    && staff.status === 401
    && ops.status === 401;

  return NextResponse.json({
    check: "smart-cafe-review-preview-connectivity",
    result: passed ? "passed" : "not-ready",
    databaseConnected: system.database === "connected",
    configuredTables: system.configuredTables,
    upstreamHttpStatus: table.status,
    menuHttpStatus: menu.status,
    unauthenticatedStaffHttpStatus: staff.status,
    unauthenticatedOpsHttpStatus: ops.status,
    workloadIdentityPresent,
    note: "Read-only synthetic checks; no staff, customer, order, or payment data changed.",
  }, { status: passed ? 200 : 503, headers: { "Cache-Control": "no-store" } });
}
