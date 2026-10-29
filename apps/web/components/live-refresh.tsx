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

    const refresh = (force = false) => {
      if (document.visibilityState !== "visible") return;
      const now = Date.now();
      if (force || now - lastRefresh >= 1200) {
        lastRefresh = now;
        router.refresh();
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
