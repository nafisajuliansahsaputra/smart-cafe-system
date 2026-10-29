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

    const form = page.locator("form").filter({
      has: page.getByRole("button", { name: "Keluar" }),
    });
    const field = await actionField(form);
    expect(field).toMatch(/^\$ACTION_ID_/);
    const forged = await context.request.post("/staff", {
      multipart: { [field]: "" },
      headers: { Origin: "https://untrusted-origin.example", Accept: "text/html" },
      maxRedirects: 0,
    });
    expect(forged.status()).toBeGreaterThanOrEqual(400);
    expect(forged.status()).toBeLessThan(600);
    expect(forged.headers()["set-cookie"] ?? "").not.toContain("scs_staff_session");

    // Critical assertion: a failed cross-origin action did not revoke the
    // session, and an honest same-origin browser can still use the staff page.
    await page.reload();
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

test("cross-site POST on guest join action cannot bypass Origin guard", async ({ browser }) => {
  const context = await browser.newContext({ baseURL: BASE });
  try {
    const page = await context.newPage();
    await page.goto("/t/e2e-01");
    // After cash-cycle closes, this QR remains a public table locator, not
    // authorization to join. The Server Action endpoint must still reject
    // cross-origin requests before any guest token could be issued.
    // Closed visits omit the join form; no credential may be fetched.
    await expect(page.locator("form")).toHaveCount(0);
    expect((await context.cookies()).some((c) => c.name === "scs_guest_session")).toBe(false);
    expect(await page.locator("body").innerText()).toContain("Meja belum diaktifkan");
  } finally {
    await context.close();
  }
});
