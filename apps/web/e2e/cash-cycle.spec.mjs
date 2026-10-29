import { test, expect } from "@playwright/test";

// Fully synthetic acceptance on an ephemeral docker-compose instance.
// Never run against a public domain or real merchant database.
const BASE = process.env.E2E_BASE_URL ?? "http://localhost:3000";
const PASSWORD = "e2e-browser-only-secret-2026";
const ITEM = "cccccccc-cccc-4ccc-8ccc-cccccccc1111";
const TABLE = "E2E-01";
const ALIAS = "e2e-01";

async function login(browser, role) {
  const context = await browser.newContext({
    baseURL: BASE, ignoreHTTPSErrors: process.env.E2E_PROXY_TLS === "true",
  });
  const page = await context.newPage();
  await page.goto("/staff/login");
  await page.getByLabel("Nama pengguna").fill(`e2e-${role}`);
  await page.getByLabel("Kata sandi").fill(PASSWORD);
  await page.getByRole("button", { name: "Masuk", exact: true }).click();
  await expect(page).toHaveURL(/\/staff$/);
  return { context, page };
}

test("separate staff roles and guest complete the real cash/receipt visit", async ({ browser }) => {
  const contexts = [];
  async function makeContext() {
    const ctx = await browser.newContext({
    baseURL: BASE, ignoreHTTPSErrors: process.env.E2E_PROXY_TLS === "true",
  });
    contexts.push(ctx);
    return ctx;
  }
  async function staff(role) {
    const user = await login(browser, role);
    contexts.push(user.context);
    return user.page;
  }

  try {
    const waiter = await staff("waiter");
    const cashier = await staff("cashier");
    const kitchen = await staff("kitchen");

    // Role routing is protected independently of UI navigation links.
    const kitchenOcc = await kitchen.goto("/staff/orders");
    expect(kitchenOcc?.status()).toBe(404);
    const waiterOcc = await waiter.goto("/staff/orders");
    expect(waiterOcc?.status()).toBe(404);
    const cashierKitchen = await cashier.goto("/staff/kitchen");
    expect(cashierKitchen?.status()).toBe(404);

    // OCC subscribes BEFORE the guest submits; the order must arrive via
    // SSE and router.refresh, with no manual navigation or refresh.
    await cashier.goto("/staff/orders");
    await expect(cashier.getByText("Live · otomatis")).toBeVisible();

    // Cashier watches the SAME table from a different authenticated tab,
    // before the waiter changes it. Event-backed updates must render without
    // a manual reload or a full-page navigation.
    const cashierTables = await cashier.context().newPage();
    await cashierTables.goto("/staff/tables");
    await expect(cashierTables.getByText("Live · otomatis")).toBeVisible();
    const watchedTable = cashierTables.locator("article").filter({
      has: cashierTables.getByRole("heading", { name: `Meja ${TABLE}` }),
    });
    await expect(watchedTable).toContainText("TERSEDIA");

    // Waiter must physically verify the table before opening the visit.
    await waiter.goto("/staff/tables");
    const tableCard = waiter.locator("article").filter({
      has: waiter.getByRole("heading", { name: `Meja ${TABLE}` }),
    });
    await expect(tableCard).toContainText("TERSEDIA");
    await tableCard.getByRole("checkbox").check();
    await tableCard.getByRole("button", { name: "Buka kunjungan" }).click();

    const proofInput = waiter.getByLabel("Kode bergabung sementara");
    await expect(proofInput).toBeVisible();
    await expect(watchedTable).toContainText("TERISI", { timeout: 25000 });
    const proof = await proofInput.inputValue();
    expect(proof).toMatch(/^[A-Za-z0-9_-]{43}$/);
    // No proof may be exposed by the public QR landing.
    const guestContext = await makeContext();
    const guest = await guestContext.newPage();
    await guest.goto(`/t/${ALIAS}`);
    await expect(guest.getByRole("heading", { name: "Gabung ke kunjungan meja" })).toBeVisible();
    expect(await guest.locator("body").innerText()).not.toContain(proof);
    await guest.getByLabel("Nama panggilan").fill("E2E Visitor");
    await guest.getByLabel("Kode bergabung dari waiter").fill(proof);
    await guest.getByRole("button", { name: "Gabung dan lihat menu" }).click();
    await expect(guest).toHaveURL(/\/visit$/);
    await expect(guest.getByRole("heading", { name: "Pilih sesuatu yang enak." })).toBeVisible();
    const guestCookie = (await guestContext.cookies()).find((c) => c.name === "scs_guest_session");
    expect(guestCookie).toMatchObject({ httpOnly: true, sameSite: "Strict", path: "/visit" });
    expect(guestCookie?.value).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(await guest.evaluate(() => localStorage.length)).toBe(0);
    expect(await guest.evaluate(() => document.cookie.includes("scs_guest_session"))).toBe(false);
    expect(await guest.content()).not.toContain(guestCookie.value);

    // Physical game loans are independent from food/cash, and cannot be
    // digitally marked "delivered" until waiter verifies the physical unit.
    await guest.goto("/visit/games");
    await expect(guest.getByRole("heading", { name: "Pinjam permainan fisik." })).toBeVisible();
    await expect(guest.getByRole("heading", { name: "UNO Demo" })).toBeVisible();
    await guest.getByRole("checkbox", {
      name: /Saya akan menjaga dan mengembalikan permainan/,
    }).check();
    await guest.getByRole("button", { name: "Ajukan pinjam" }).click();
    await expect(guest.getByText("Menunggu waiter")).toBeVisible();
    await waiter.goto("/staff/games");
    await expect(waiter.getByText("REQUESTED", { exact: true })).toBeVisible();
    await waiter.getByRole("checkbox", {
      name: /Sudah memeriksa ketersediaan unit fisik/,
    }).check();
    await waiter.getByRole("button", { name: "Alokasikan unit" }).click();
    await expect(waiter.getByText("ASSIGNED", { exact: true })).toBeVisible();
    await expect(waiter.getByText(/UNO-E2E-001/)).toBeVisible();
    await waiter.getByLabel("Masukkan kode pada unit fisik").fill("UNO-E2E-001");
    await waiter.getByRole("checkbox", {
      name: /Unit telah diserahkan langsung ke pelanggan/,
    }).check();
    await waiter.getByRole("button", { name: "Konfirmasi serah-terima" }).click();
    await expect(waiter.getByText("IN_USE", { exact: true })).toBeVisible();
    await guest.goto("/visit/games");
    await expect(guest.getByText("Sedang dipinjam")).toBeVisible();

    // Required + optional modifier groups must contribute to SERVER-priced cart.
    await guest.goto(`/visit/menu/${ITEM}`);
    await expect(guest.getByRole("heading", { name: "E2E Latte" })).toBeVisible();
    await guest.getByRole("group", { name: /Ukuran/ }).getByRole("radio", { name: /Large/ }).check();
    await guest.getByRole("group", { name: /Susu/ }).getByRole("radio", { name: /Oat Milk/ }).check();
    await guest.getByLabel(/Jumlah porsi/).fill("1");
    await guest.getByRole("button", { name: "Simpan ke keranjang" }).click();
    await expect(guest).toHaveURL(/\/visit\/cart\?notice=updated/);
    await expect(guest.getByRole("heading", { name: "E2E Latte" })).toBeVisible();
    await expect(guest.locator(".guest-cart-total")).toContainText("35.000");

    // Guest checkout records an unpaid order, NEVER optimistic PAID.
    await guest.getByRole("checkbox", { name: /Saya setuju membuat pesanan cash/ }).check();
    await guest.getByRole("button", { name: "Buat pesanan cash" }).click();
    await expect(guest).toHaveURL(/\/visit\/orders\/[0-9a-f-]{36}\?notice=submitted/);
    const orderId = new URL(guest.url()).pathname.split("/").at(-1);
    expect(orderId).toMatch(/^[0-9a-f-]{36}$/);
    await expect(guest.getByText("AWAITING_CASH", { exact: true })).toBeVisible();
    await expect(guest.getByRole("heading", { name: "Struk digital" })).toHaveCount(0);
    await expect(cashier.locator(`a[href="/staff/orders/${orderId}"]`)).toBeVisible({
      timeout: 25000,
    });

    // A second guest using a valid visit proof cannot read this guest's order.
    const strangerContext = await makeContext();
    const stranger = await strangerContext.newPage();
    await stranger.goto(`/t/${ALIAS}`);
    await stranger.getByLabel("Nama panggilan").fill("E2E Other Visitor");
    await stranger.getByLabel("Kode bergabung dari waiter").fill(proof);
    await stranger.getByRole("button", { name: "Gabung dan lihat menu" }).click();
    await expect(stranger).toHaveURL(/\/visit$/);
    const denied = await stranger.goto(`/visit/orders/${orderId}`);
    expect(denied?.status()).toBe(404);
    const foreignStream = await strangerContext.request.get(
      `/visit/live?topic=order&id=${orderId}`,
    );
    expect(foreignStream.status()).toBe(404);
    const guestOrderStream = await guestContext.request.get(
      "/visit/live?topic=order&id=not-a-uuid",
    );
    expect(guestOrderStream.status()).toBe(400);

    // Unsettled cash must block visit close even if waiter confirms in UI.
    await waiter.goto("/staff/tables");
    const unsettledCard = waiter.locator("article").filter({
      has: waiter.getByRole("heading", { name: `Meja ${TABLE}` }),
    });
    await unsettledCard.getByRole("checkbox", {
      name: /Semua pesanan dan pembayaran telah selesai/,
    }).check();
    await unsettledCard.getByRole("button", { name: "Tutup kunjungan" }).click();
    await expect(waiter.getByText(/masih ada pesanan\/pembayaran belum selesai/)).toBeVisible();
    await expect(unsettledCard).toContainText("TERISI");

    // Kitchen connects before cashier approval; the new ticket must appear
    // without a manual Kitchen page reload.
    await kitchen.goto("/staff/kitchen");
    await expect(kitchen.getByText("Live · otomatis")).toBeVisible();

    // The kitchen device loses connectivity while the cashier approves.
    // The missed event must be recovered from the durable cursor on reconnect,
    // with no manual navigation or page reload.
    await kitchen.context().setOffline(true);
    await expect(kitchen.getByText("Menyambungkan ulang · segarkan jika perlu")).toBeVisible();
    await cashier.goto(`/staff/orders/${orderId}`);
    await expect(cashier.getByText("AWAITING_CASH", { exact: true })).toBeVisible();
    await cashier.getByRole("checkbox", { name: /Saya telah memeriksa pesanan dan meja tamu/ }).check();
    await cashier.getByRole("button", { name: "Setujui untuk dapur" }).click();
    await expect(cashier.getByText("ACCEPTED", { exact: true })).toBeVisible();
    // Guest and kitchen should update when cashier acts, without reload.
    await expect(guest.getByRole("heading", { name: "ACCEPTED" })).toBeVisible({
      timeout: 25000,
    });
    await expect(guest.getByText("AWAITING_CASH", { exact: true })).toBeVisible();
    await kitchen.context().setOffline(false);
    await expect(kitchen.getByText("Live · otomatis")).toBeVisible({
      timeout: 25000,
    });
    await expect(kitchen.getByText("ACCEPTED", { exact: true })).toBeVisible({
      timeout: 25000,
    });

    // Kitchen cooks, then marks READY using actual ticket state.
    await kitchen.getByRole("checkbox", { name: /Mulai menyiapkan pesanan ini/ }).check();
    await kitchen.getByRole("button", { name: "Mulai masak" }).click();
    await expect(kitchen.getByText("PREPARING", { exact: true })).toBeVisible();
    // Waiter subscribes while kitchen is preparing; READY should flow
    // independently over SSE to the waiter task board.
    await waiter.goto("/staff/waiter");
    await expect(waiter.getByText("Live · otomatis")).toBeVisible();
    await kitchen.getByRole("checkbox", { name: /Semua item sudah disiapkan/ }).check();
    await kitchen.getByRole("button", { name: "Siap diantar" }).click();
    await expect(kitchen.getByText("READY", { exact: true })).toBeVisible();

    // Opening waiter confirms handover and physical cash including backend change.
    // No navigation: the waiter must receive the READY state via SSE.
    await expect(waiter.getByText("READY", { exact: true })).toBeVisible();
    await waiter.getByRole("checkbox", { name: /Pesanan sudah diserahkan langsung ke meja/ }).check();
    await waiter.getByRole("button", { name: "Tandai sudah diantar" }).click();
    await expect(waiter.getByText("SERVED", { exact: true })).toBeVisible();
    await waiter.getByLabel("Uang tunai diterima (rupiah)").fill("50000");
    await waiter.getByRole("checkbox", { name: /Saya telah menerima dan menghitung uang fisik/ }).check();
    await waiter.getByRole("button", { name: "Catat uang diterima" }).click();
    await expect(waiter.getByText("LUNAS", { exact: true })).toBeVisible();
    await expect(waiter.locator(".staff-change")).toContainText("15.000");

    // The guest page stays mounted while waiter collects cash. A live
    // status notification causes the server to fetch and render its receipt.
    await expect(guest.getByText("PAID", { exact: true })).toBeVisible({
      timeout: 25000,
    });
    await expect(guest.getByRole("heading", { name: "Struk digital" })).toBeVisible();
    await expect(guest.getByText(/Struk #/)).toBeVisible();
    await expect(guest.locator(".guest-cart-total")).toContainText("35.000");

    // Even after cash is PAID and food SERVED, a physical unit in use
    // must block table closure until the waiter has inspected its return.
    await waiter.goto("/staff/tables");
    const gameUnreturned = waiter.locator("article").filter({
      has: waiter.getByRole("heading", { name: `Meja ${TABLE}` }),
    });
    await gameUnreturned.getByRole("checkbox", {
      name: /Semua pesanan dan pembayaran telah selesai/,
    }).check();
    await gameUnreturned.getByRole("button", { name: "Tutup kunjungan" }).click();
    await expect(waiter.getByText(/outstanding physical game loan|permainan/i)).toBeVisible();
    await expect(gameUnreturned).toContainText("TERISI");

    await waiter.goto("/staff/games");
    await expect(waiter.getByText("IN_USE", { exact: true })).toBeVisible();
    await waiter.getByLabel("Masukkan kode pada unit fisik").fill("UNO-E2E-001");
    await waiter.getByRole("checkbox", {
      name: /Unit diterima kembali dan sudah diperiksa kondisinya/,
    }).check();
    await waiter.getByRole("button", { name: "Tandai dikembalikan" }).click();
    await expect(waiter.getByText("Belum ada pinjaman aktif")).toBeVisible();
    await guest.goto("/visit/games");
    await expect(guest.getByText("Sudah dikembalikan")).toBeVisible();

    // Table closure is now eligible; server checks both paid orders and game.
    await waiter.goto("/staff/tables");
    const closeCard = waiter.locator("article").filter({
      has: waiter.getByRole("heading", { name: `Meja ${TABLE}` }),
    });
    await expect(closeCard).toContainText("TERISI");
    await closeCard.getByRole("checkbox", {
      name: /Semua pesanan dan pembayaran telah selesai/,
    }).check();
    await closeCard.getByRole("button", { name: "Tutup kunjungan" }).click();
    await expect(waiter.getByText(/Kunjungan meja berhasil ditutup/)).toBeVisible();
    await expect(watchedTable).toContainText("TERSEDIA", { timeout: 25000 });
    await guest.goto("/visit");
    await expect(guest.getByRole("heading", { name: "Belum bergabung ke meja" })).toBeVisible();
  } finally {
    for (const ctx of contexts) await ctx.close();
  }
});
