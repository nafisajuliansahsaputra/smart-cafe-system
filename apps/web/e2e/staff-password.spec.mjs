import { expect, test } from "@playwright/test";

// Disposable synthetic account, separate from all other browser test identities.
const BASE = process.env.E2E_BASE_URL ?? "http://localhost:3000";
const OLD = "e2e-browser-only-secret-2026";
const NEXT = "e2e-password-rotated-2026";

async function signIn(page, password) {
  await page.goto("/staff/login");
  await page.getByLabel("Nama pengguna").fill("e2e-password");
  await page.getByLabel("Kata sandi", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Masuk", exact: true }).click();
}

test("staff self-service rotation revokes other browser sessions and old password", async ({ browser }) => {
  const opts = { baseURL: BASE, ignoreHTTPSErrors: process.env.E2E_PROXY_TLS === "true" };
  const first = await browser.newContext(opts);
  const second = await browser.newContext(opts);
  try {
    const a = await first.newPage();
    const b = await second.newPage();
    await signIn(a, OLD);
    await signIn(b, OLD);
    await expect(a).toHaveURL(/\/staff$/);
    await expect(b).toHaveURL(/\/staff$/);
    const oldToken = (await second.cookies()).find((cookie) => cookie.name === "scs_staff_session");
    expect(oldToken?.httpOnly).toBe(true);

    await a.goto("/staff/account");
    await expect(a.getByRole("heading", { name: "Ganti kata sandi" })).toBeVisible();
    await a.getByLabel("Kata sandi saat ini").fill("incorrect-current-password");
    await a.getByLabel("Kata sandi baru", { exact: false }).fill(NEXT);
    await a.getByLabel("Ulangi kata sandi baru").fill(NEXT);
    await a.getByRole("button", { name: /Ganti kata sandi dan keluar/ }).click();
    await expect(a).toHaveURL(/\/staff\/account\?notice=incorrect$/);
    await expect(a.getByText("Kata sandi saat ini tidak benar.")).toBeVisible();
    await expect(b.getByRole("heading", { name: "Ruang operasional" })).toBeVisible();

    await a.getByLabel("Kata sandi saat ini").fill(OLD);
    await a.getByLabel("Kata sandi baru", { exact: false }).fill(NEXT);
    await a.getByLabel("Ulangi kata sandi baru").fill(NEXT);
    await a.getByRole("button", { name: /Ganti kata sandi dan keluar/ }).click();
    await expect(a).toHaveURL(/\/staff\/login\?status=password-changed$/);
    expect((await first.cookies()).some((cookie) => cookie.name === "scs_staff_session")).toBe(false);
    await b.goto("/staff");
    await expect(b).toHaveURL(/\/staff\/login/);
    expect((await second.cookies()).find((cookie) => cookie.name === "scs_staff_session")?.value)
      .toBe(oldToken?.value);

    await signIn(a, OLD);
    await expect(a.getByText("Login atau kata sandi tidak valid.")).toBeVisible();
    await signIn(a, NEXT);
    await expect(a).toHaveURL(/\/staff$/);
  } finally {
    await first.close();
    await second.close();
  }
});
