import { test, expect } from "@playwright/test";

const BASE = process.env.E2E_BASE_URL ?? "http://localhost:3000";
const PASSWORD = "e2e-browser-only-secret-2026";

async function login(browser, role) {
  const context = await browser.newContext({
    baseURL: BASE,
    ignoreHTTPSErrors: process.env.E2E_PROXY_TLS === "true",
  });
  const page = await context.newPage();
  await page.goto("/staff/login");
  await page.getByLabel("Nama pengguna").fill("e2e-" + role);
  await page.getByLabel("Kata sandi").fill(PASSWORD);
  await page.getByRole("button", { name: "Masuk", exact: true }).click();
  await expect(page).toHaveURL(/\/staff$/);
  return { page, context };
}

test("manager creates a real table and controls its operational state", async ({ browser }) => {
  const { page, context } = await login(browser, "manager");
  try {
    await page.goto("/staff");
    await expect(page.getByRole("link", { name: "Atur meja & QR/NFC" })).toBeVisible();
    await page.getByRole("link", { name: "Atur meja & QR/NFC" }).click();
    await expect(page.getByRole("heading", { name: "Pengaturan meja" })).toBeVisible();
    await page.getByLabel(/Kode meja/).fill("E2E-M7-12");
    await page.getByLabel("Alias untuk QR/NFC").fill("e2e-m7-12");
    await page.getByLabel("Kapasitas kursi").fill("4");
    await page.getByRole("checkbox", { name: "Konfirmasi meja dan alias belum digunakan" }).check();
    await page.getByRole("button", { name: "Daftarkan meja" }).click();
    await expect(page.getByText("Meja baru sudah dibuat di database.")).toBeVisible();

    const row = page.getByRole("row").filter({ hasText: "E2E-M7-12" });
    await expect(row).toContainText("/t/e2e-m7-12");
    await row.getByLabel("Kapasitas").fill("6");
    await row.getByLabel("Status meja").selectOption("OUT_OF_SERVICE");
    await row.getByRole("checkbox", { name: "Konfirmasi perubahan" }).check();
    await row.getByRole("button", { name: "Simpan meja" }).click();
    await expect(page.getByText("Kapasitas atau status meja berhasil diperbarui.")).toBeVisible();
    const updated = page.getByRole("row").filter({ hasText: "E2E-M7-12" });
    await expect(updated).toContainText("OUT_OF_SERVICE");
    await expect(updated.getByLabel("Kapasitas")).toHaveValue("6");
    await page.screenshot({ path: "test-results/operational-table-settings.png", fullPage: true });

    await page.setViewportSize({ width: 320, height: 720 });
    await expect.poll(async () => page.evaluate(
      () => document.documentElement.scrollWidth - window.innerWidth
    )).toBeLessThanOrEqual(1);
    await expect(updated.getByRole("button", { name: "Simpan meja" })).toBeVisible();
  } finally {
    await context.close();
  }
});

test("cashier, waiter and kitchen cannot access table master administration", async ({ browser }) => {
  for (const role of ["cashier", "waiter", "kitchen"]) {
    const { page, context } = await login(browser, role);
    try {
      const response = await page.goto("/staff/table-settings");
      expect(response?.status()).toBe(404);
      await expect(page.getByRole("link", { name: "Atur meja & QR/NFC" })).toHaveCount(0);
    } finally {
      await context.close();
    }
  }
});
