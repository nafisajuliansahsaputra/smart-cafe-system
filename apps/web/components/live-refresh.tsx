"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";

type Mode = "connecting" | "live" | "reconnecting";

const MIN_RETRY_MS = 1000;
const MAX_RETRY_MS = 30000;

/**
 * Browser receives change signals only. Never place session tokens, cursors
 * or order data inside JavaScript, a URL or the EventSource payload.
 */
export function LiveRefresh({ url }: { url: string }) {
  const router = useRouter();
  const [mode, setMode] = useState<Mode>("connecting");

  useEffect(() => {
    let source: EventSource | null = null;
    let lastRefresh = 0;
    let trailingTimer: ReturnType<typeof setTimeout> | null = null;
    let retryTimer: ReturnType<typeof setTimeout> | null = null;
    let retries = 0;
    let offline = !navigator.onLine;
    let unauthorized = false;

    const clearTrailing = () => {
      if (trailingTimer !== null) clearTimeout(trailingTimer);
      trailingTimer = null;
    };
    const clearRetry = () => {
      if (retryTimer !== null) clearTimeout(retryTimer);
      retryTimer = null;
    };
    const closeSource = () => {
      source?.close();
      source = null;
    };
    const refresh = (force = false) => {
      if (document.visibilityState !== "visible" || offline) return;
      const now = Date.now();
      const remaining = force ? 0 : Math.max(0, 1200 - (now - lastRefresh));
      if (remaining === 0) {
        clearTrailing();
        lastRefresh = now;
        router.refresh();
      } else if (trailingTimer === null) {
        // Coalesce bursts but never discard a change after a ready event.
        trailingTimer = setTimeout(() => {
          trailingTimer = null;
          if (document.visibilityState !== "visible" || offline) return;
          lastRefresh = Date.now();
          router.refresh();
        }, remaining);
      }
    };
    const canConnect = () =>
      !offline && !unauthorized && document.visibilityState === "visible";

    const connect = () => {
      if (!canConnect() || source) return;
      setMode("connecting");
      const opened = new EventSource(url);
      source = opened;
      opened.onopen = () => {
        if (source !== opened) return;
        clearRetry();
        retries = 0;
        setMode("live");
      };
      opened.onerror = () => {
        if (source !== opened) return;
        closeSource();
        setMode("reconnecting");
        // Do not let browser auto-retry unboundedly on proxy/429 errors.
        // Application owns a capped exponential backoff.
        scheduleRetry();
      };
      opened.addEventListener("changed", () => {
        if (source !== opened) return;
        setMode("live");
        refresh();
      });
      opened.addEventListener("auth-expired", () => {
        if (source !== opened) return;
        unauthorized = true;
        closeSource();
        clearRetry();
        setMode("reconnecting");
        refresh(true); // protected server page handles expired authorization
      });
      opened.addEventListener("unavailable", () => {
        if (source === opened) setMode("reconnecting");
      });
      opened.addEventListener("ready", () => {
        if (source !== opened) return;
        setMode("live");
        refresh(); // recover missed events from authoritative server state
      });
    };
    const scheduleRetry = () => {
      if (!canConnect() || retryTimer !== null) return;
      const delay = Math.min(MAX_RETRY_MS, MIN_RETRY_MS * (2 ** Math.min(5, retries)));
      retries += 1;
      retryTimer = setTimeout(() => {
        retryTimer = null;
        connect();
      }, delay);
    };
    const disconnect = () => {
      clearTrailing();
      clearRetry();
      closeSource();
      setMode("reconnecting");
    };
    const onVisibility = () => {
      if (document.visibilityState === "hidden") disconnect();
      else if (canConnect()) {
        retries = 0;
        refresh(true);
        connect();
      }
    };
    const onOffline = () => {
      offline = true;
      disconnect();
    };
    const onOnline = () => {
      offline = false;
      if (canConnect()) {
        retries = 0;
        refresh(true);
        connect();
      }
    };

    document.addEventListener("visibilitychange", onVisibility);
    window.addEventListener("offline", onOffline);
    window.addEventListener("online", onOnline);
    if (!offline) connect();

    return () => {
      document.removeEventListener("visibilitychange", onVisibility);
      window.removeEventListener("offline", onOffline);
      window.removeEventListener("online", onOnline);
      disconnect();
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
