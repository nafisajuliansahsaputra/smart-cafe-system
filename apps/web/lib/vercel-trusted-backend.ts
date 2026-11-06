import "server-only";

import { headers } from "next/headers";

/**
 * Add Vercel's short-lived, platform-issued request OIDC token only for our
 * protected Smart Cafe backend Preview. Vercel Trusted Sources validates the
 * signature and caller project/environment before forwarding the request.
 *
 * No static bypass key, database credentials, or browser-visible token.
 * A human must explicitly allow smart-cafe-review Preview -> API Preview
 * in the backend project's Deployment Protection / Trusted Sources settings.
 */
export async function trustedBackendHeaders(baseUrl: string): Promise<Record<string, string>> {
  if (process.env.VERCEL_ENV !== "preview") return {};

  let target: URL;
  try {
    target = new URL(baseUrl);
  } catch {
    return {};
  }

  // Never forward a Vercel identity token to an arbitrary upstream URL.
  if (
    target.protocol !== "https:" ||
    !/^smart-cafe-api-review-[a-z0-9]+\.vercel\.app$/.test(target.hostname) ||
    target.username ||
    target.password ||
    target.port
  ) {
    return {};
  }

  const issuedToken = (await headers()).get("x-vercel-oidc-token");
  return issuedToken ? { "x-vercel-trusted-oidc-idp-token": issuedToken } : {};
}
