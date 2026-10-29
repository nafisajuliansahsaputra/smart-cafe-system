import "server-only";

import { readLiveCursor } from "./live-cursor";

const encoder = new TextEncoder();
const MAX_STREAM_MS = 55000;
const RETRY_DELAY_MS = 3000;

function nap(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    if (signal.aborted) return resolve();
    const finish = () => {
      clearTimeout(timer);
      signal.removeEventListener("abort", finish);
      resolve();
    };
    const timer = setTimeout(finish, ms);
    signal.addEventListener("abort", finish, { once: true });
  });
}

/**
 * Change-only EventSource bridge. PG NOTIFY wakes backend long polls; durable
 * scoped cursor is checked after wake, and after missed wakeup/reconnect.
 * No cursor, access token or order body is sent to the browser.
 */
export async function liveCursorStream(
  request: Request,
  backendPath: string,
  token: string,
): Promise<Response> {
  const initial = await readLiveCursor(backendPath, token, undefined, request.signal);
  if ([401,403,404].includes(initial.status)) {
    return new Response(null, { status: initial.status, headers: { "Cache-Control": "private, no-store" } });
  }
  if (initial.status !== 200 || initial.cursor === null) {
    return new Response(null, { status: 503, headers: { "Cache-Control": "private, no-store" } });
  }

  let last = initial.cursor;
  const abort = new AbortController();
  const disconnect = () => abort.abort();
  request.signal.addEventListener("abort", disconnect, { once: true });
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      const emit = (event: string) => {
        controller.enqueue(encoder.encode(`event: ${event}\ndata: {}\n\n`));
      };
      void (async () => {
        try {
          controller.enqueue(encoder.encode("retry: 4000\n\n"));
          emit("ready");
          const deadline = Date.now() + MAX_STREAM_MS;
          while (!abort.signal.aborted && Date.now() < deadline) {
            const next = await readLiveCursor(backendPath, token, last, abort.signal);
            if (abort.signal.aborted) break;
            if ([401,403,404].includes(next.status)) {
              emit("auth-expired");
              break;
            }
            if (next.status === 200 && next.cursor !== null) {
              if (next.cursor !== last) {
                last = next.cursor;
                emit("changed");
              } else {
                controller.enqueue(encoder.encode(": heartbeat\n\n"));
              }
            } else {
              emit("unavailable");
              await nap(RETRY_DELAY_MS, abort.signal);
            }
          }
        } catch {
          // Includes network cancellation; no sensitive upstream details logged.
        } finally {
          abort.abort();
          request.signal.removeEventListener("abort", disconnect);
          try { controller.close(); } catch { /* cancelled by client */ }
        }
      })();
    },
    cancel() {
      disconnect();
      request.signal.removeEventListener("abort", disconnect);
    },
  });
  return new Response(stream, {
    status: 200,
    headers: {
      "Content-Type": "text/event-stream; charset=utf-8",
      "Cache-Control": "private, no-store, no-transform",
      "X-Accel-Buffering": "no",
      "X-Content-Type-Options": "nosniff",
    },
  });
}
