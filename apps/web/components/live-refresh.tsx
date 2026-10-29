"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";

type Mode = "connecting" | "live" | "reconnecting";

/**
 * Browser receives only change signals. router.refresh() re-reads the
 * authorized server component instead of rendering untrusted stream data.
 */
export function LiveRefresh({ url }: { url: string }) {
  const router = useRouter();
  const [mode, setMode] = useState<Mode>("connecting");

  useEffect(() => {
    let source: EventSource | null = null;
    let lastRefresh = 0;
    let trailingTimer: ReturnType<typeof setTimeout> | null = null;

    const clearTrailing = () => {
      if (trailingTimer !== null) clearTimeout(trailingTimer);
      trailingTimer = null;
    };
    const refresh = (force = false) => {
      if (document.visibilityState !== "visible") return;
      const now = Date.now();
      const remaining = force ? 0 : Math.max(0, 1200 - (now - lastRefresh));
      if (remaining === 0) {
        clearTrailing();
        lastRefresh = now;
        router.refresh();
      } else if (trailingTimer === null) {
        // Never discard a change that arrives just after the ready event:
        // coalesce fast events but always perform the trailing refresh.
        trailingTimer = setTimeout(() => {
          trailingTimer = null;
          if (document.visibilityState !== "visible") return;
          lastRefresh = Date.now();
          router.refresh();
        }, remaining);
      }
    };
    const connect = () => {
      if (document.visibilityState !== "visible" || source) return;
      setMode("connecting");
      source = new EventSource(url);
      source.onopen = () => setMode("live");
      source.onerror = () => setMode("reconnecting");
      source.addEventListener("changed", () => refresh());
      source.addEventListener("auth-expired", () => {
        setMode("reconnecting");
        source?.close();
        source = null;
        refresh(true);
      });
      source.addEventListener("unavailable", () => setMode("reconnecting"));
      source.addEventListener("ready", () => {
        setMode("live");
        // Guard an initial-render race where a mutation occurred just before
        // the connection started observing.
        refresh();
      });
    };
    const onVisibility = () => {
      if (document.visibilityState === "hidden") {
        clearTrailing();
        source?.close();
        source = null;
        setMode("reconnecting");
      } else {
        refresh(true);
        connect();
      }
    };
    document.addEventListener("visibilitychange", onVisibility);
    connect();

    return () => {
      document.removeEventListener("visibilitychange", onVisibility);
      clearTrailing();
      source?.close();
      source = null;
    };
  }, [router, url]);

  return (
    <span className="staff-live-indicator" role="status">
      {mode === "live" ? "Live · otomatis" :
        mode === "connecting" ? "Menghubungkan pembaruan..." :
          "Menyambungkan ulang · segarkan jika perlu"}
    </span>
  );
}
