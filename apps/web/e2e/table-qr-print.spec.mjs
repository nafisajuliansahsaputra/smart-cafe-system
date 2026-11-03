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
  return { context, page };
}

test("manager prints an offline SVG QR encoding the actual table URL", async ({ browser }) => {
  const { context, page } = await login(browser, "manager");
  try {
    await page.goto("/staff/tables/registry");
    await page.getByRole("link", { name: "Cetak QR meja" }).click();
    await expect(page.getByRole("heading", { name: "Cetak QR meja" })).toBeVisible();

    const select = page.getByLabel("Meja untuk dicetak");
    await select.selectOption("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaa1111");
    const qr = page.locator("svg[data-qr-url]");
    await expect(qr).toHaveCount(1);
    const url = await qr.getAttribute("data-qr-url");
    expect(new URL(url).pathname).toBe("/t/e2e-01");
    expect(new URL(url).origin).toBe(new URL(BASE).origin);
    await expect(qr.locator("path")).toHaveAttribute("d", /^M\d+,\d+h1v1h-1z/);
    await expect(page.getByRole("button", { name: "Unduh QR SVG" })).toBeEnabled();

    const download = page.waitForEvent("download");
    await page.getByRole("button", { name: "Unduh QR SVG" }).click();
    const file = await download;
    expect(file.suggestedFilename()).toBe("smart-cafe-meja-E2E-01.svg");

    await page.setViewportSize({ width: 320, height: 740 });
    await expect.poll(() => page.evaluate(
      () => document.documentElement.scrollWidth - window.innerWidth,
    )).toBeLessThanOrEqual(1);
    await page.screenshot({
      path: "test-results/operational-table-qr.png", fullPage: true,
    });
  } finally {
    await context.close();
  }
});

test("QR print route is denied to cashier, kitchen and waiter", async ({ browser }) => {
  for (const role of ["cashier", "kitchen", "waiter"]) {
    const { context, page } = await login(browser, role);
    try {
      const response = await page.goto("/staff/tables/registry/print");
      expect(response?.status()).toBe(404);
    } finally {
      await context.close();
    }
  }
});
