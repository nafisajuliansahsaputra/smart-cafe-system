import "server-only";

import { createHash } from "node:crypto";

type Snapshot = { status: number; payload: unknown };

const INTERVAL_MS = 5000;
const MAX_LIFETIME_MS = 55000;
const encoder = new TextEncoder();

function checksum(value: unknown): string {
  return createHash("sha256").update(JSON.stringify(value) ?? "null").digest("hex");
}

function wait(ms: number, signal: AbortSignal): Promise<void> {
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
 * Credential-safe SSE. The browser gets change notifications only, never API
 * payloads, bearer tokens, receipt content or cross-guest order snapshots.
 * DB/API authorization is re-checked on every 5-second observation.
 *
 * This is snapshot-polling over an SSE connection, not an outbox event
 * dispatcher. Keep it bounded until production capacity controls exist.
 */
export async function liveStream(
  request: Request,
  observe: () => Promise<Snapshot>,
): Promise<Response> {
  const initial = await observe();
  if (initial.status === 401 || initial.status === 403 || initial.status === 404) {
    return new Response(null, {
      status: initial.status,
      headers: { "Cache-Control": "private, no-store" },
    });
  }
  if (initial.status !== 200 || initial.payload === null) {
    return new Response(null, {
      status: 503,
      headers: { "Cache-Control": "private, no-store" },
    });
  }

  let last = checksum(initial.payload);
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
          const deadline = Date.now() + MAX_LIFETIME_MS;
          while (!abort.signal.aborted && Date.now() < deadline) {
            await wait(INTERVAL_MS, abort.signal);
            if (abort.signal.aborted) break;
            const next = await observe();
            if (abort.signal.aborted) break;
            if ([401, 403, 404].includes(next.status)) {
              emit("auth-expired");
              break;
            }
            if (next.status === 200 && next.payload !== null) {
              const current = checksum(next.payload);
              if (current !== last) {
                last = current;
                emit("changed");
              } else {
                controller.enqueue(encoder.encode(": heartbeat\n\n"));
              }
            } else {
              emit("unavailable");
            }
          }
        } catch {
          // A disconnected browser may close the stream while a poll is in
          // flight. No customer body or credential is logged.
        } finally {
          abort.abort();
          request.signal.removeEventListener("abort", disconnect);
          try { controller.close(); } catch { /* reader already cancelled */ }
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
