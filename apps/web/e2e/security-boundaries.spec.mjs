import { expect, test } from "@playwright/test";

// Real Chromium + Next.js Server Actions + API. Never run on a real merchant.
const BASE = process.env.E2E_BASE_URL ?? "http://localhost:3000";
const PASSWORD = "e2e-browser-only-secret-2026";

function actionField(form) {
  return form.evaluate((element) => {
    const names = Array.from(new FormData(element).keys());
    return names.find((name) => name.startsWith("$ACTION_ID_")) ?? null;
  });
}

test("forged Origin cannot invoke authenticated staff logout", async ({ browser }) => {
  const context = await browser.newContext({ baseURL: BASE });
  try {
    const page = await context.newPage();
    await page.goto("/staff/login");
    await page.getByLabel("Nama pengguna").fill("e2e-cashier");
    await page.getByLabel("Kata sandi").fill(PASSWORD);
    await page.getByRole("button", { name: "Masuk", exact: true }).click();
    await expect(page).toHaveURL(/\/staff$/);

    const cookie = (await context.cookies()).find((c) => c.name === "scs_staff_session");
    expect(cookie).toMatchObject({ httpOnly: true, sameSite: "Strict", path: "/staff" });
    expect(await page.evaluate(() => document.cookie.includes("scs_staff_session"))).toBe(false);
    expect(await page.content()).not.toContain(cookie.value);

    // Intercept the actual React Server Action POST from the authenticated
    // browser, rather than relying on hidden form fields after hydration.
    await page.route("**/staff", async (route) => {
      if (route.request().method() !== "POST") return route.continue();
      return route.continue({
        headers: {
          ...route.request().headers(),
          origin: "https://untrusted-origin.example",
        },
      });
    });
    const [forged] = await Promise.all([
      page.waitForResponse((response) =>
        new URL(response.url()).pathname === "/staff"
        && response.request().method() === "POST"),
      page.getByRole("button", { name: "Keluar" }).click(),
    ]);
    expect(forged.status()).toBeGreaterThanOrEqual(400);
    expect(forged.status()).toBeLessThan(600);
    expect(forged.headers()["set-cookie"] ?? "").not.toContain("scs_staff_session");
    await page.unroute("**/staff");

    // A rejected cross-origin logout must not revoke the genuine staff token.
    await page.goto("/staff");
    await expect(page.getByRole("heading", { name: "Ruang operasional" })).toBeVisible();
    expect((await context.cookies()).find((c) => c.name === "scs_staff_session")?.value)
      .toBe(cookie.value);
  } finally {
    await context.close();
  }
});

test("oversized Server Action body cannot create staff session", async ({ browser }) => {
  const context = await browser.newContext({ baseURL: BASE });
  try {
    const page = await context.newPage();
    await page.goto("/staff/login");
    const actionId = await actionField(page.locator("form"));
    expect(actionId).toMatch(/^\$ACTION_ID_/);

    // Explicit 32kb action limit; no extra data accepted by auth.
    const overLimit = await context.request.post("/staff/login", {
      multipart: {
        [actionId]: "",
        login: "e2e-cashier",
        password: PASSWORD,
        padding: "x".repeat(64 * 1024),
      },
      headers: { Origin: BASE, Accept: "text/html" },
      maxRedirects: 0,
    });
    expect(overLimit.status()).toBeGreaterThanOrEqual(400);
    expect((await context.cookies()).some((c) => c.name === "scs_staff_session")).toBe(false);

    // Normal login remains possible after the rejected oversized POST.
    await page.getByLabel("Nama pengguna").fill("e2e-cashier");
    await page.getByLabel("Kata sandi").fill(PASSWORD);
    await page.getByRole("button", { name: "Masuk", exact: true }).click();
    await expect(page).toHaveURL(/\/staff$/);
  } finally {
    await context.close();
  }
});

test("closed public QR cannot issue a guest session", async ({ browser }) => {
  const context = await browser.newContext({ baseURL: BASE });
  try {
    const page = await context.newPage();
    await page.goto("/t/e2e-01");
    // After cash-cycle closes, the QR remains a public locator, not
    // authorization to join. No guest form or credential should be exposed.
    // Closed visits omit the join form; no credential may be fetched.
    await expect(page.locator("form")).toHaveCount(0);
    expect((await context.cookies()).some((c) => c.name === "scs_guest_session")).toBe(false);
    expect(await page.locator("body").innerText()).toContain("Meja belum diaktifkan");
  } finally {
    await context.close();
  }
});
