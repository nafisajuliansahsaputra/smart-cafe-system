import { NextResponse } from "next/server";
import { headers } from "next/headers";
import { guestApi } from "@/lib/guest-server";

export const dynamic = "force-dynamic";
export const maxDuration = 60;

/**
 * Temporary, read-only Preview gate for verifying frontend -> protected
 * Spring Boot -> Aiven. This does not expose upstream credentials or
 * proxy arbitrary paths, and never changes café state.
 */
export async function GET() {
  if (process.env.VERCEL_ENV !== "preview") {
    return new Response(null, { status: 404 });
  }

  const oidcAvailable = (await headers()).has("x-vercel-oidc-token");
  const upstream = await guestApi(
    "/api/v1/tables/UNCONFIGURED-REVIEW-SMOKE/entry",
    "GET",
  );

  const passed = upstream.status === 404;
  return NextResponse.json(
    {
      check: "smart-cafe-review-preview-connectivity",
      result: passed ? "passed" : "not-ready",
      upstreamHttpStatus: upstream.status,
      workloadIdentityPresent: oidcAvailable,
      note: "Read-only synthetic non-existent table lookup; no staff or payment data.",
    },
    { status: passed ? 200 : 503, headers: { "Cache-Control": "no-store" } },
  );
}
