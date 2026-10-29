import { cookies } from "next/headers";
import { GUEST_COOKIE, UUID_PATTERN } from "@/lib/guest-server";
import { liveCursorStream } from "@/lib/live-cursor-stream";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function GET(request: Request): Promise<Response> {
  const url = new URL(request.url);
  const topic = url.searchParams.get("topic");
  const id = url.searchParams.get("id");
  let path: string;
  if (topic === "orders" && id === null && url.searchParams.size === 1) {
    path = "/api/v1/me/live-cursor?topic=orders";
  } else if (topic === "order" && id && UUID_PATTERN.test(id) &&
             url.searchParams.size === 2) {
    path = `/api/v1/me/live-cursor?topic=order&orderId=${id}`;
  } else {
    return new Response(null, { status: 400 });
  }
  const token = (await cookies()).get(GUEST_COOKIE)?.value;
  if (!token) return new Response(null, { status: 401 });
  return liveCursorStream(request, path, token);
}
