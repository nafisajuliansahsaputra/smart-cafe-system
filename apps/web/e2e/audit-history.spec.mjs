import { test, expect } from "@playwright/test";

const BASE = process.env.E2E_BASE_URL ?? "http://localhost:3000";
const PASSWORD = "e2e-browser-only-secret-2026";

async function login(browser, role) {
  const context = await browser.newContext({
    baseURL: BASE, ignoreHTTPSErrors: process.env.E2E_PROXY_TLS === "true",
  });
  const page = await context.newPage();
  await page.goto("/staff/login");
  await page.getByLabel("Nama pengguna").fill("e2e-" + role);
  await page.getByLabel("Kata sandi").fill(PASSWORD);
  await page.getByRole("button", { name: "Masuk", exact: true }).click();
  await expect(page).toHaveURL(/\/staff$/);
  return { context, page };
}

test("manager can review immutable server audit entries from real table creation", async ({ browser }) => {
  const { context, page } = await login(browser, "manager");
  try {
    await page.goto("/staff/tables/registry");
    await expect(page.getByRole("heading", { name: "Inventaris meja" })).toBeVisible();
    await page.getByLabel(/Kode meja/).fill("AUDIT-M7");
    await page.getByLabel(/Alias publik URL/).fill("audit-m7");
    await page.getByLabel(/Kapasitas tamu/).fill("2");
    await page.getByRole("checkbox", {
      name: "Saya sudah memverifikasi meja fisik dan alias unik untuk QR/NFC",
    }).check();
    await page.getByRole("button", { name: "Daftarkan meja" }).click();
    await expect(page.getByText("Meja berhasil ditambahkan.", { exact: false })).toBeVisible();

    await page.goto("/staff");
    await expect(page.getByRole("link", { name: "Lihat riwayat audit" })).toBeVisible();
    await page.getByRole("link", { name: "Lihat riwayat audit" }).click();
    await expect(page.getByRole("heading", { name: "Jejak aktivitas staf" })).toBeVisible();
    await page.getByLabel("Cari aktivitas, objek, atau staf").fill("CAFE_TABLE_CREATED");
    await page.getByRole("button", { name: "Cari aktivitas" }).click();
    await expect(page.getByRole("row").filter({ hasText: "CAFE TABLE CREATED" }).first())
      .toBeVisible();
    await expect(page.getByRole("table")).toContainText("e2e-manager");
    await expect(page.locator("main")).not.toContainText("accessToken");
    await page.screenshot({ path: "test-results/operational-audit-manager.png", fullPage: true });
    await page.setViewportSize({ width: 320, height: 720 });
    await expect.poll(() => page.evaluate(
      () => document.documentElement.scrollWidth - window.innerWidth
    )).toBeLessThanOrEqual(1);
    await page.getByLabel("Cari aktivitas, objek, atau staf").fill("NO_SUCH_AUDIT_EVENT");
    await page.getByRole("button", { name: "Cari aktivitas" }).click();
    await expect(page.getByText("Tidak ada aktivitas yang sesuai.")).toBeVisible();
  } finally {
    await context.close();
  }
});

test("audit records are not exposed to cashier, kitchen, waiter or guests", async ({ browser }) => {
  const anonymous = await browser.newPage({ baseURL: BASE });
  try {
    const response = await anonymous.goto("/staff/audit");
    expect(response?.status()).toBe(307);
  } finally {
    await anonymous.close();
  }
  for (const role of ["cashier", "kitchen", "waiter"]) {
    const { context, page } = await login(browser, role);
    try {
      const response = await page.goto("/staff/audit");
      expect(response?.status()).toBe(404);
    } finally {
      await context.close();
    }
  }
});
