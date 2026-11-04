import { expect, test } from "@playwright/test";

// Synthetic local Docker Compose only. No real cafe/merchant endpoints.
const BASE = process.env.E2E_BASE_URL ?? "http://localhost:3000";
const PASSWORD = "e2e-browser-only-secret-2026";
const WIDTHS = [320, 375, 390, 768];

async function login(browser, role) {
  const context = await browser.newContext({
    baseURL: BASE,
    ignoreHTTPSErrors: process.env.E2E_PROXY_TLS === "true",
    viewport: { width: 320, height: 740 },
    isMobile: true,
    hasTouch: true,
  });
  const page = await context.newPage();
  await page.goto("/staff/login");
  await page.getByLabel("Nama pengguna").fill("e2e-" + role);
  await page.getByLabel("Kata sandi").fill(PASSWORD);
  await page.getByRole("button", { name: "Masuk", exact: true }).click();
  await expect(page).toHaveURL(/\/staff$/);
  return { context, page };
}

async function checkStaffMobile(page, route, width) {
  await page.setViewportSize({ width, height: 740 });
  await page.goto(route);
  await expect(page.locator("main.staff-shell")).toBeVisible();
  await expect.poll(async () => page.evaluate(
    () => document.documentElement.scrollWidth - window.innerWidth
  )).toBeLessThanOrEqual(1);
  const badCards = await page.locator(".staff-panel").evaluateAll((cards) =>
    cards.filter((card) => {
      const rect = card.getBoundingClientRect();
      return rect.left < -1 || rect.right > window.innerWidth + 1;
    }).map((card) => card.querySelector("h2,h3")?.textContent ?? "unnamed")
  );
  expect(badCards, "Staff panels must fit without document-level side scrolling").toEqual([]);
  const shortTargets = await page.locator(
    ".staff-panel a, .staff-breadcrumb a, .staff-panel button, .staff-panel .staff-check"
  ).evaluateAll((els) =>
    els.filter((el) => {
      const rect = el.getBoundingClientRect();
      return rect.width > 0 && rect.height > 0 && rect.height < 43.5;
    }).map((el) => el.textContent?.trim().slice(0, 50) ?? "?")
  );
  expect(shortTargets, "Staff links, actions and confirmations require 44px targets").toEqual([]);
}

test("waiter pages remain operable without horizontal overflow at phone and tablet widths", async ({ browser }) => {
  const { context, page } = await login(browser, "waiter");
  try {
    for (const width of WIDTHS) {
      for (const route of ["/staff", "/staff/waiter", "/staff/games"]) {
        await checkStaffMobile(page, route, width);
      }
    }
    await page.goto("/staff");
    await page.keyboard.press("Tab");
    const outline = await page.evaluate(
      () => getComputedStyle(document.activeElement).outlineStyle
    );
    expect(outline).toBe("solid");
    await expect(page.getByRole("link", { name: "Kelola permainan" })).toBeVisible();
  } finally {
    await context.close();
  }
});

test("manager incident review controls, anchor navigation and search fit on mobile", async ({ browser }) => {
  const { context, page } = await login(browser, "manager");
  try {
    for (const width of WIDTHS) {
      for (const route of ["/staff", "/staff/games?istatus=OPEN#loss-incidents"]) {
        await checkStaffMobile(page, route, width);
      }
      await expect(page.getByRole("heading", {
        name: "Laporan unit belum ditemukan",
      })).toBeVisible();
      await expect(page.locator("#loss-incidents")).toHaveAttribute(
        "aria-labelledby", "loss-incidents-title"
      );
    }
    await page.setViewportSize({ width: 320, height: 740 });
    await page.goto("/staff/games?istatus=OPEN#loss-incidents");
    await page.getByLabel(
      "Cari laporan berdasarkan unit, game, meja, pelapor atau catatan"
    ).fill("MOBILE-NOT-AN-INCIDENT");
    await page.getByRole("button", { name: "Cari laporan" }).click();
    await expect(page).toHaveURL(/#loss-incidents$/);
    await expect(page.getByText("Tidak ada laporan yang sesuai.")).toBeVisible();
    await page.getByRole("link", { name: "Reset laporan" }).click();
    await expect(page).toHaveURL(/#loss-incidents$/);
    await page.getByLabel("Filter kondisi unit").selectOption("LOST");
    await page.getByRole("button", { name: "Cari unit" }).click();
    await expect(page).toHaveURL(/#copy-inventory$/);
    await page.getByRole("link", { name: "Reset pencarian" }).click();
    await expect(page).toHaveURL(/#copy-inventory$/);
    await checkStaffMobile(page, "/staff/games#condition-history", 320);
    await page.getByLabel("Cari catatan, staf, nama game atau kode unit")
      .fill("MOBILE-NOT-A-RECORD");
    await page.getByRole("button", { name: "Cari riwayat" }).click();
    await expect(page).toHaveURL(/#condition-history$/);
    await expect(page.getByText("Riwayat pemeriksaan tidak ditemukan."))
      .toBeVisible();
    await page.getByRole("link", { name: "Reset riwayat" }).click();
    await expect(page).toHaveURL(/#condition-history$/);
  } finally {
    await context.close();
  }
});
