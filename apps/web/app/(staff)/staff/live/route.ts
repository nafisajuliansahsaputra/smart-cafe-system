import { cookies } from "next/headers";
import { liveStream } from "@/lib/live-stream";
import { STAFF_COOKIE, staffApi } from "@/lib/staff-server";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

/** No arbitrary upstream URL or query pass-through, just fixed role-gated APIs. */
export async function GET(request: Request): Promise<Response> {
  const url = new URL(request.url);
  const topic = url.searchParams.get("topic");
  const id = url.searchParams.get("id");
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
  let path: string;
  switch (topic) {
    case "orders": path = "/api/v1/ops/orders"; break;
    case "order":
      if (!id || !uuid.test(id)) return new Response(null, { status: 400 });
      path = `/api/v1/ops/orders/${id}`;
      break;
    case "kitchen": path = "/api/v1/kitchen/tickets"; break;
    case "waiter": path = "/api/v1/waiter/orders/tasks"; break;
    case "tables": path = "/api/v1/waiter/tables"; break;
    default: return new Response(null, { status: 400 });
  }
  if (topic !== "order" && id !== null) return new Response(null, { status: 400 });
  if (url.searchParams.size !== (topic === "order" ? 2 : 1)) {
    return new Response(null, { status: 400 });
  }
  const token = (await cookies()).get(STAFF_COOKIE)?.value;
  if (!token) return new Response(null, { status: 401 });
  // Each poll invokes the current backend role/session check; logout and
  // disablement close the stream on the next observation.
  return liveStream(request, () => staffApi(path, "GET", token));
}
