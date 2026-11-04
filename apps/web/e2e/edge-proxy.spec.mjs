import { expect, test } from "@playwright/test";

const BASE = process.env.E2E_BASE_URL ?? "http://localhost:3000";
test.skip(process.env.E2E_PROXY_TLS !== "true", "This case requires the ephemeral HTTPS edge.");

test("HTTPS edge replaces caller-supplied forwarded host and issues Secure cookies", async ({ browser }) => {
  const context = await browser.newContext({
    baseURL: BASE,
    ignoreHTTPSErrors: true, // ephemeral self-signed cert in CI ONLY
  });
  try {
    const page = await context.newPage();
    await page.goto("/staff/login");
    await page.route("**/staff/login", async (route) => {
      if (route.request().method() !== "POST") return route.continue();
      return route.continue({
        headers: {
          ...route.request().headers(),
          "x-forwarded-host": "attacker.invalid",
          "x-forwarded-proto": "http",
          "x-forwarded-for": "203.0.113.66",
        },
      });
    });
    await page.getByLabel("Nama pengguna").fill("e2e-cashier");
    await page.getByLabel("Kata sandi").fill("e2e-browser-only-secret-2026");
    await page.getByRole("button", { name: "Masuk", exact: true }).click();
    await expect(page).toHaveURL(/\/staff$/);

    const cookie = (await context.cookies()).find((item) => item.name === "scs_staff_session");
    expect(cookie).toMatchObject({
      httpOnly: true,
      secure: true,
      sameSite: "Strict",
      path: "/staff",
    });
    expect(await page.evaluate(() => document.cookie.includes("scs_staff_session"))).toBe(false);
    await page.unroute("**/staff/login");
  } finally {
    await context.close();
  }
});
