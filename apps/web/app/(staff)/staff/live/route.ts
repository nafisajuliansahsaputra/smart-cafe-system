import { cookies } from "next/headers";
import { liveStream } from "@/lib/live-stream";
import { liveCursorStream } from "@/lib/live-cursor-stream";
import { STAFF_COOKIE, staffApi } from "@/lib/staff-server";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

/** Allowlisted, same-origin, server-token-only status watch. */
export async function GET(request: Request): Promise<Response> {
  const url = new URL(request.url);
  const topic = url.searchParams.get("topic");
  const id = url.searchParams.get("id");
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
  let path: string;
  let legacySnapshot = false;
  switch (topic) {
    case "orders":
      path = "/api/v1/ops/live-cursor?topic=orders"; break;
    case "order":
      if (!id || !uuid.test(id)) return new Response(null, { status: 400 });
      path = `/api/v1/ops/live-cursor?topic=order&orderId=${id}`; break;
    case "kitchen":
      path = "/api/v1/kitchen/live-cursor?topic=kitchen"; break;
    case "waiter":
      path = "/api/v1/waiter/live-cursor?topic=waiter"; break;
    case "tables":
      // Table openings/closures do not yet write ORDER outbox events.
      // Retain existing authenticated table snapshots until table events exist.
      path = "/api/v1/waiter/tables";
      legacySnapshot = true;
      break;
    default: return new Response(null, { status: 400 });
  }
  if (topic !== "order" && id !== null) return new Response(null, { status: 400 });
  if (url.searchParams.size !== (topic === "order" ? 2 : 1)) {
    return new Response(null, { status: 400 });
  }
  const token = (await cookies()).get(STAFF_COOKIE)?.value;
  if (!token) return new Response(null, { status: 401 });
  return legacySnapshot
    ? liveStream(request, () => staffApi(path, "GET", token))
    : liveCursorStream(request, path, token);
}
