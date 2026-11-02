import { test, expect } from "@playwright/test";

test("portfolio landing exposes an honest, responsive demo without technical dashboard clutter", async ({ page }) => {
  await page.setViewportSize({ width: 1400, height: 900 });
  await page.goto("/");
  await expect(page.getByRole("heading", { name: /Good coffee.*Better flow/i })).toBeVisible();
  await expect(page.getByRole("link", { name: /Jelajahi demo/i })).toHaveAttribute("href", "/showcase");
  await expect(page.getByRole("link", { name: /Portal staf/i }).first()).toHaveAttribute("href", "/staff/login");
  await expect(page.getByText("Demo menggunakan data simulasi, bukan transaksi nyata.")).toBeVisible();
  await expect(page.locator("body")).not.toContainText("API dan database terhubung");
  await page.screenshot({ path: "test-results/portfolio-home-desktop.png", fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect.poll(() => page.evaluate(
    () => document.documentElement.scrollWidth - window.innerWidth
  )).toBeLessThanOrEqual(1);
  await page.screenshot({ path: "test-results/portfolio-home-mobile.png", fullPage: true });
});

test("isolated showcase demonstrates cashier approval separate from actual cash collection", async ({ page }) => {
  await page.goto("/showcase");
  await expect(page.getByRole("note")).toContainText("Tidak terhubung ke API transaksi");
  await expect(page.getByRole("heading", { name: "What sounds good?" })).toBeVisible();
  await expect(page.getByRole("button", { name: /Cashier \/ OCC/ })).toHaveAttribute("aria-pressed", "false");
  await page.getByRole("button", { name: "Tambah Butter Croissant" }).click();
  await expect(page.getByRole("output", { name: "Kuantitas Butter Croissant" })).toHaveText("1");
  await page.screenshot({ path: "test-results/portfolio-demo-guest.png", fullPage: true });
  await page.getByRole("button", { name: /Kirim pesanan cash/ }).click();
  await expect(page.getByRole("button", { name: /Cashier \/ OCC/ })).toHaveAttribute("aria-pressed", "true");
  await expect(page.getByText("Cash belum dianggap lunas sebelum waiter mencatat penerimaan uang.")).toBeVisible();
  await page.getByRole("button", { name: /Setujui untuk dapur/ }).click();
  await expect(page.getByRole("button", { name: /Kitchen \/ KDS/ })).toHaveAttribute("aria-pressed", "true");
  await page.screenshot({ path: "test-results/portfolio-demo-kitchen.png", fullPage: true });
  await page.getByRole("button", { name: /Tandai siap diantar/ }).click();
  await expect(page.getByRole("button", { name: "Waiter" })).toHaveAttribute("aria-pressed", "true");
  await page.getByRole("button", { name: /Tandai sudah diantar/ }).click();
  await expect(page.getByText("Cash belum dianggap lunas sebelum waiter mencatat penerimaan uang.")).toBeVisible();
  await page.getByRole("button", { name: /Catat penerimaan cash/ }).click();
  await expect(page.getByRole("button", { name: "Customer" })).toHaveAttribute("aria-pressed", "true");
  await expect(page.getByText("NOT A FISCAL RECEIPT · NO REAL PAYMENT")).toBeVisible();
  await expect(page.getByText("Rp 56.000")).toBeVisible();
  await page.screenshot({ path: "test-results/portfolio-demo-receipt.png", fullPage: true });
  await page.getByRole("button", { name: /Coba simulasi lagi/ }).click();
  await expect(page.getByRole("heading", { name: "What sounds good?" })).toBeVisible();
  await expect(page.getByRole("output", { name: "Kuantitas Butter Croissant" })).toHaveText("0");
  expect(await page.evaluate(() => localStorage.length)).toBe(0);
});

test("showcase remains contained on a narrow mobile screen with keyboard-operable role changes", async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 720 });
  await page.goto("/showcase");
  await expect(page.getByRole("heading", { name: "What sounds good?" })).toBeVisible();
  await expect.poll(() => page.evaluate(
    () => document.documentElement.scrollWidth - window.innerWidth
  )).toBeLessThanOrEqual(1);
  const firstRole = page.getByRole("button", { name: "Customer" });
  await firstRole.focus();
  await page.keyboard.press("Tab");
  await expect(page.getByRole("button", { name: /Cashier \/ OCC/ })).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("button", { name: /Cashier \/ OCC/ })).toHaveAttribute("aria-pressed", "true");
  await expect.poll(() => page.evaluate(
    () => document.documentElement.scrollWidth - window.innerWidth
  )).toBeLessThanOrEqual(1);
});
