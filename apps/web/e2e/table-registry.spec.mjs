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

test("manager creates a physical table and cannot disable it during a live visit", async ({ browser }) => {
  const manager = await login(browser, "manager");
  const waiter = await login(browser, "waiter");
  try {
    await manager.page.goto("/staff/tables");
    await manager.page.getByRole("link", { name: "Kelola inventaris meja" }).click();
    await expect(manager.page.getByRole("heading", { name: "Inventaris meja" })).toBeVisible();

    await manager.page.getByLabel(/Kode meja/).fill("E2E-M7");
    await manager.page.getByLabel(/Alias publik URL/).fill("e2e-m7");
    await manager.page.getByLabel(/Kapasitas tamu/).fill("4");
    await manager.page.getByRole("checkbox", {
      name: "Saya sudah memverifikasi meja fisik dan alias unik untuk QR/NFC",
    }).check();
    await manager.page.getByRole("button", { name: "Daftarkan meja" }).click();
    await expect(manager.page.getByText("Meja berhasil ditambahkan.", { exact: false })).toBeVisible();

    const card = manager.page.locator(".staff-task-card").filter({ hasText: "Meja E2E-M7" });
    await expect(card).toContainText("TERSEDIA");
    await expect(card.getByRole("link", { name: "/t/e2e-m7" })).toHaveAttribute("href", "/t/e2e-m7");

    await card.getByLabel("Kapasitas", { exact: true }).fill("6");
    await card.getByLabel("Status", { exact: true }).selectOption("OUT_OF_SERVICE");
    await card.getByRole("checkbox", { name: /Saya telah memeriksa status meja/ }).check();
    await card.getByRole("button", { name: "Simpan status / kapasitas" }).click();
    await expect(manager.page.getByText("Kapasitas atau status meja berhasil diperbarui.")).toBeVisible();
    const updated = manager.page.locator(".staff-task-card").filter({ hasText: "Meja E2E-M7" });
    await expect(updated).toContainText("Kapasitas: 6");
    await expect(updated).toContainText("NONAKTIF");

    await updated.getByLabel("Status", { exact: true }).selectOption("AVAILABLE");
    await updated.getByRole("checkbox", { name: /Saya telah memeriksa status meja/ }).check();
    await updated.getByRole("button", { name: "Simpan status / kapasitas" }).click();
    await expect(manager.page.locator(".staff-task-card").filter({
      hasText: "Meja E2E-M7",
    })).toContainText("TERSEDIA");

    await manager.page.screenshot({
      path: "test-results/operational-table-registry.png", fullPage: true,
    });
    await waiter.page.goto("/staff/tables");
    const waiterCard = waiter.page.locator(".staff-task-card").filter({ hasText: "Meja E2E-M7" });
    await waiterCard.getByRole("checkbox", {
      name: "Saya sudah memeriksa meja dan tamu yang datang",
    }).check();
    await waiterCard.getByRole("button", { name: "Buka kunjungan" }).click();
    await expect(waiter.page.getByLabel("Kode bergabung sementara")).toBeVisible();

    // Manager still has the previously rendered AVAILABLE form. Server must
    // reject disabling it now, even if the old page has not refreshed.
    const managerCard = manager.page.locator(".staff-task-card").filter({
      hasText: "Meja E2E-M7",
    });
    await managerCard.getByLabel("Status", { exact: true }).selectOption("OUT_OF_SERVICE");
    await managerCard.getByRole("checkbox", { name: /Saya telah memeriksa status meja/ }).check();
    await managerCard.getByRole("button", { name: "Simpan status / kapasitas" }).click();
    await expect(manager.page.getByText(/meja masih dipakai/)).toBeVisible();
    await expect(manager.page.locator(".staff-task-card").filter({
      hasText: "Meja E2E-M7",
    })).toContainText("Sedang digunakan");
    await manager.page.setViewportSize({ width: 320, height: 740 });
    await expect.poll(() => manager.page.evaluate(
      () => document.documentElement.scrollWidth - window.innerWidth
    )).toBeLessThanOrEqual(1);

    // Search is now handled by PostgreSQL, not a truncated local array.
    await manager.page.goto("/staff/tables/registry?q=E2E-M7");
    await expect(manager.page.getByRole("heading", { name: "Daftar meja (1)" })).toBeVisible();
    await expect(manager.page.locator(".staff-task-card")).toHaveCount(1);
    await manager.page.goto("/staff/tables/registry/print?q=E2E-M7");
    const qr = manager.page.locator("svg[data-qr-url]");
    await expect(qr).toHaveCount(1);
    expect(new URL(await qr.getAttribute("data-qr-url")).pathname).toBe("/t/e2e-m7");

    await manager.page.goto("/staff/tables/registry?page=1001");
    await expect(manager.page.getByRole("alert")).toContainText("Pencarian tidak valid");
  } finally {
    await manager.context.close();
    await waiter.context.close();
  }
});

test("regular cashier/kitchen/waiter cannot access the table registry", async ({ browser }) => {
  for (const role of ["cashier", "kitchen", "waiter"]) {
    const { context, page } = await login(browser, role);
    try {
      const response = await page.goto("/staff/tables/registry");
      expect(response?.status()).toBe(404);
    } finally {
      await context.close();
    }
  }
});
