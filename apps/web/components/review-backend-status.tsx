"use client";

import { useEffect, useState } from "react";

type ReviewHealth = {
  backend?: string;
  configuredTables?: number;
};

/**
 * The badge is advisory, never an authorization decision. Keep the homepage
 * responsive while a sleeping free-tier Java backend starts up.
 */
export function ReviewBackendStatus() {
  const [status, setStatus] = useState("Memeriksa backend review...");

  useEffect(() => {
    const controller = new AbortController();
    let alive = true;
    async function probe() {
      for (let attempt = 0; attempt < 2 && alive; attempt++) {
        try {
          const response = await fetch("/api/review-health", {
            cache: "no-store",
            signal: controller.signal,
          });
          const data: ReviewHealth = await response.json();
          if (response.ok && data.backend === "connected"
              && typeof data.configuredTables === "number") {
            if (alive) {
              setStatus("Backend terhubung / " + data.configuredTables + " meja terdaftar");
            }
            return;
          }
        } catch {
          if (!alive) return;
        }
        if (attempt === 0) {
          await new Promise<void>((resolve) => setTimeout(resolve, 1500));
        }
      }
      if (alive) setStatus("Showcase online / backend review sedang aktif");
    }
    void probe();
    return () => {
      alive = false;
      controller.abort();
    };
  }, []);

  return <span aria-live="polite">{status}</span>;
}
