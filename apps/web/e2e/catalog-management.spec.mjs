import { test, expect } from "@playwright/test";

const BASE = process.env.E2E_BASE_URL ?? "http://localhost:3000";
const PASSWORD = "e2e-browser-only-secret-2026";

async function logIn(browser, role) {
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

test("manager can manage actual category, hidden item, modifier, stock and publication", async ({ browser }) => {
  const { context, page } = await logIn(browser, "manager");
  try {
    await page.goto("/staff");
    await expect(page.getByRole("link", { name: "Kelola katalog" })).toBeVisible();
    await page.getByRole("link", { name: "Kelola katalog" }).click();
    await expect(page.getByRole("heading", { name: "Menu & stok" })).toBeVisible();

    await page.getByText("Tambah kategori baru").click();
    await page.getByLabel("Nama kategori").fill("E2E Menu Drinks");
    await page.getByLabel(/Slug unik/).fill("e2e-menu-drinks");
    await page.getByRole("checkbox", { name: /Saya sudah memeriksa nama dan slug/ }).check();
    await page.getByRole("button", { name: "Buat kategori" }).click();
    await expect(page.getByText("Kategori baru berhasil dibuat.")).toBeVisible();
    await expect(page.getByText("E2E Menu Drinks", { exact: true }).first()).toBeVisible();

    await page.getByText("Buat item menu").click();
    await page.getByLabel("Nama produk").fill("E2E Web Espresso");
    await page.getByLabel("Deskripsi singkat (opsional)").fill(
      "Espresso dengan susu segar, disajikan dingin.",
    );
    await page.getByLabel("Kategori", { exact: true }).selectOption({ label: "E2E Menu Drinks" });
    await page.getByLabel("Harga dasar (IDR)").fill("19900");
    await page.getByRole("checkbox", { name: /Saya memahami item belum ditampilkan/ }).check();
    await page.getByRole("button", { name: "Tambah produk sebagai HIDDEN" }).click();
    await expect(page.getByText(/Item dibuat sebagai HIDDEN/)).toBeVisible();
    await page.getByLabel("Cari produk, kategori atau status").fill("E2E Web Espresso");
    await page.getByRole("button", { name: "Cari", exact: true }).click();
    await expect(page.getByRole("heading", { name: "Daftar produk (1)" })).toBeVisible();
    await expect(page.getByRole("row").filter({ hasText: "E2E Web Espresso" })).toHaveCount(1);

    const row = page.getByRole("row").filter({ hasText: "E2E Web Espresso" });
    await expect(row).toContainText("HIDDEN");
    await row.getByRole("link", { name: "Edit / stok / varian" }).click();
    await expect(page.getByRole("heading", { name: "E2E Web Espresso" })).toBeVisible();
    await expect(page.getByLabel("Deskripsi menu")).toHaveValue(
      "Espresso dengan susu segar, disajikan dingin.",
    );

    await page.getByText("Tambah grup varian baru").click();
    await page.getByLabel("Nama grup").fill("E2E Milk Choice");
    await page.getByRole("checkbox", { name: "Pelanggan wajib memilih satu pilihan" }).check();
    await page.getByRole("checkbox", { name: /Saya memahami grup harus diberi pilihan/ }).check();
    await page.getByRole("button", { name: "Tambah grup" }).click();
    await expect(page.getByText("Grup varian dibuat.", { exact: false })).toBeVisible();
    await expect(page.getByRole("heading", { name: /E2E Milk Choice/ })).toBeVisible();

    await page.getByText("Tambah pilihan varian").click();
    await page.getByLabel("Kode unik pilihan").fill("regular");
    await page.getByLabel("Label pelanggan").fill("Regular Milk");
    await page.getByLabel("Tambah/kurangi harga (IDR)").fill("1500");
    await page.getByRole("checkbox", { name: /Saya memahami pilihan baru/ }).check();
    await page.getByRole("button", { name: "Tambah pilihan" }).click();
    await expect(page.getByText("Pilihan varian berhasil ditambahkan.")).toBeVisible();
    await expect(page.getByText(/Regular Milk.*1\.500/)).toBeVisible();

    await page.getByLabel("Kebijakan kuantitas").selectOption("limited");
    await page.getByLabel("Jumlah tersisa (wajib jika terbatas)").fill("12");
    await page.getByLabel("Alasan penyesuaian stok").fill("Penyesuaian stok sintetik E2E");
    await page.getByRole("checkbox", { name: "Saya telah memeriksa stok secara fisik" }).check();
    await page.getByRole("button", { name: "Simpan stok dengan audit" }).click();
    await expect(page.getByText("Stok berhasil diperbarui")).toBeVisible();
    await expect(page.getByText(/Sisa 12 unit/)).toBeVisible();

    await page.getByLabel("Deskripsi menu").fill("Espresso yang kaya rasa dengan susu.");
    await page.getByLabel("Status penjualan").selectOption("AVAILABLE");
    await page.getByRole("checkbox", { name: "Saya telah memeriksa perubahan harga dan status publikasi" }).check();
    await page.getByRole("button", { name: "Simpan perubahan menu" }).click();
    await expect(page.getByText("Nama, harga, kategori atau publikasi item berhasil diperbarui.")).toBeVisible();
    await expect(page.getByText(/Status:.*AVAILABLE/)).toBeVisible();
    await expect(page.getByLabel("Deskripsi menu")).toHaveValue(
      "Espresso yang kaya rasa dengan susu.",
    );

    await page.screenshot({
      path: "test-results/operational-catalog-manager.png", fullPage: true,
    });
    await page.setViewportSize({ width: 320, height: 720 });
    await expect.poll(() => page.evaluate(
      () => document.documentElement.scrollWidth - window.innerWidth
    )).toBeLessThanOrEqual(1);

    await page.goto("/staff/catalog?page=1001");
    await expect(page.locator("section.staff-panel[role='alert']")).toContainText(
      "Format pencarian atau halaman tidak valid",
    );
  } finally {
    await context.close();
  }
});

test("cashier and kitchen cannot reach catalog administration", async ({ browser }) => {
  for (const role of ["cashier", "kitchen", "waiter"]) {
    const { context, page } = await logIn(browser, role);
    try {
      const response = await page.goto("/staff/catalog");
      expect(response?.status()).toBe(404);
      const detail = await page.goto("/staff/catalog/cccccccc-cccc-4ccc-8ccc-cccccccc1111");
      expect(detail?.status()).toBe(404);
    } finally {
      await context.close();
    }
  }
});
