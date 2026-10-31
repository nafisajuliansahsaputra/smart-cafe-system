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
    expect((await waiter.goto("/staff/cash-shifts"))?.status()).toBe(404);
    expect((await kitchen.goto("/staff/cash-shifts"))?.status()).toBe(404);

    // Open a physical till shift before the guest's cash tender.
    await cashier.goto("/staff/cash-shifts");
    await expect(cashier.getByRole("heading", { name: "Rekonsiliasi kas per shift" }))
      .toBeVisible();
    await cashier.getByLabel("Uang awal laci (IDR)").fill("10000");
    await cashier.getByRole("checkbox", { name: "Uang awal telah dihitung langsung" }).check();
    await cashier.getByRole("button", { name: "Buka shift" }).click();
    await expect(cashier.getByRole("heading", { name: "Shift aktif" })).toBeVisible();
    await expect(cashier.getByLabel("Shift kasir aktif")).toContainText("10.000");

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
    // Assert the synthetic catalogue entry and include the rendered status in
    // failure output, so an unavailable API cannot look like empty inventory.
    // Diagnostic contract: verify the same scoped guest token against
    // the internal backend in this disposable CI environment, then verify
    // Next.js server-side parsing separately.
    const gameBackendResponse = await guestContext.request.get(
      "http://127.0.0.1:8080/api/v1/me/games",
      { headers: { Authorization: `Bearer ${guestCookie.value}` } },
    );
    expect(gameBackendResponse.status()).toBe(200);
    const gameBackendData = await gameBackendResponse.json();
    expect(gameBackendData.data.some((game) => game.name === "UNO Demo")).toBe(true);
    // Browser must actually render the API-backed entry; no fake status attribute.
    await expect(guest.getByRole("heading", { name: "UNO Demo" })).toBeVisible();
    // Live request must arrive on an already open waiter screen.
    await waiter.goto("/staff/games");
    await expect(waiter.getByText("Belum ada pinjaman aktif")).toBeVisible();
    await expect(waiter.getByText("Live · otomatis")).toBeVisible({ timeout: 25000 });
    await guest.getByRole("checkbox", {
      name: /Saya akan menjaga dan mengembalikan permainan/,
    }).check();
    await guest.getByRole("button", { name: "Ajukan pinjam" }).click();
    await expect(guest.getByText("Menunggu waiter")).toBeVisible();
    await expect(waiter.getByText("REQUESTED", { exact: true })).toBeVisible({ timeout: 25000 });
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
    await expect(guest.getByText("Sedang dipinjam")).toBeVisible({ timeout: 25000 });
    // Customer can request pickup, but stock remains borrowed until staff inspection.
    await guest.getByRole("checkbox", { name: /Saya ingin waiter mengambil permainan ini/ }).check();
    await guest.getByRole("button", { name: "Minta pengambilan" }).click();
    await expect(guest.getByText("Menunggu pengambilan waiter")).toBeVisible();
    await expect(waiter.getByText("RETURN_PENDING", { exact: true })).toBeVisible({ timeout: 25000 });

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

    // Default cash browser must not expose sandbox-only payment controls.
    await expect(guest.getByRole("heading", {
      name: "Simulasi pembayaran digital (SANDBOX)",
    })).toHaveCount(0);
    await expect(guest.getByRole("button", {
      name: "Simulasikan pesanan QRIS",
    })).toHaveCount(0);

    // Guest checkout records an unpaid order, NEVER optimistic PAID.
    await guest.getByRole("checkbox", { name: /Saya setuju membuat pesanan cash/ }).check();
    await guest.getByRole("button", { name: "Buat pesanan cash" }).click();
    await expect(guest).toHaveURL(/\/visit\/orders\/[0-9a-f-]{36}\?notice=submitted/);
    const orderId = new URL(guest.url()).pathname.split("/").at(-1);
    expect(orderId).toMatch(/^[0-9a-f-]{36}$/);
    await expect(guest.getByText("AWAITING_CASH", { exact: true })).toBeVisible();
    await expect(guest.getByRole("heading", { name: "Struk digital" })).toHaveCount(0);
    const unpaidPrintable = await guestContext.request.get("/visit/orders/" + orderId + "/receipt");
    expect(unpaidPrintable.status()).toBe(404);
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
    const foreignPrintable = await strangerContext.request.get("/visit/orders/" + orderId + "/receipt");
    expect(foreignPrintable.status()).toBe(404);
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

    // Recorded cash is the real received amount MINUS recorded change.
    // Closing produces immutable discrepancy evidence, never more PAID orders.
    await cashier.goto("/staff/cash-shifts");
    const till = cashier.getByLabel("Shift kasir aktif");
    await expect(till).toContainText("35.000");
    await expect(till).toContainText("45.000");
    await cashier.getByLabel("Uang fisik yang dihitung (IDR)").fill("44000");
    await cashier.getByLabel("Catatan pemeriksaan").fill(
      "Synthetic closing count, all waiter cash handed over",
    );
    await cashier.getByRole("checkbox", {
      name: "Saya sudah menghitung uang fisik dan menyetujui pencatatan selisih",
    }).check();
    await cashier.getByRole("button", { name: "Tutup shift & catat selisih" }).click();
    await expect(cashier.getByLabel("Riwayat shift kasir")).toContainText("-Rp");
    await expect(cashier.getByLabel("Riwayat shift kasir")).toContainText("1.000");
    await expect(cashier.getByRole("heading", { name: "Buka shift kasir" })).toBeVisible();

    // Cash report is server-backed: only this finalized receipt is counted.
    await cashier.goto("/staff/reports");
    await expect(cashier.getByRole("heading", { name: "Laporan cash harian" })).toBeVisible();
    await expect(cashier.getByLabel("Ringkasan penerimaan cash")).toContainText("35.000");
    await expect(cashier.getByRole("table").first()).toContainText("E2E Latte");
    const ledgerTable = cashier.getByRole("table", { name: "Transaksi cash lunas" });
    await expect(ledgerTable).toBeVisible();
    await expect(ledgerTable).toContainText("35.000");
    await expect(ledgerTable.getByRole("link", { name: "Lihat pesanan" })).toHaveAttribute(
      "href", "/staff/orders/" + orderId,
    );
    expect(await ledgerTable.innerText()).not.toContain("E2E Visitor");
    await expect(cashier.getByText("Rincian struk cash lunas")).toBeVisible();

    // Restricted report download reuses PAID CASH receipt totals.
    const csvLink = cashier.getByRole("link", { name: "Unduh CSV laporan" });
    await expect(csvLink).toBeVisible();
    const csvPath = await csvLink.getAttribute("href");
    expect(csvPath).toMatch(/^\/staff\/reports\/export\?date=\d{4}-\d{2}-\d{2}$/);
    const exportResponse = await cashier.context().request.get(csvPath);
    expect(exportResponse.status()).toBe(200);
    expect(exportResponse.headers()["content-type"]).toMatch(/^text\/csv;/);
    expect(exportResponse.headers()["content-disposition"]).toMatch(/^attachment; filename="cash-daily-/);
    expect(exportResponse.headers()["cache-control"]).toContain("no-store");
    const csv = await exportResponse.text();
    expect(csv).toContain('"gross_cash_collected"');
    expect(csv).toContain('"35000"');
    expect(csv).toContain('"E2E Latte"');
    expect(csv).not.toContain("E2E Visitor");
    expect(csv).not.toContain("scs_guest_session");

    expect((await cashier.context().request.get(
      "/staff/reports/export?date=2026-02-30",
    )).status()).toBe(400);
    expect((await cashier.context().request.get(
      "/staff/reports/export?date=2026-10-01&date=2026-10-02",
    )).status()).toBe(400);
    const anonymousCsv = await makeContext();
    expect((await anonymousCsv.request.get(csvPath)).status()).toBe(401);
    expect((await waiter.context().request.get(csvPath)).status()).toBe(404);
    expect((await kitchen.context().request.get(csvPath)).status()).toBe(404);

    const deniedWaiterReport = await waiter.goto("/staff/reports");
    expect(deniedWaiterReport?.status()).toBe(404);
    const deniedKitchenReport = await kitchen.goto("/staff/reports");
    expect(deniedKitchenReport?.status()).toBe(404);
    await expect(guest.getByRole("link", { name: "Cetak atau simpan PDF" })).toBeVisible();
    const printablePage = await guest.goto("/visit/orders/" + orderId + "/receipt");
    expect(printablePage?.status()).toBe(200);
    await expect(guest.getByRole("heading", { name: "Struk pembayaran" })).toBeVisible();
    await expect(guest.getByRole("button", { name: "Cetak / simpan PDF" })).toBeVisible();
    await expect(guest.getByRole("table", { name: "Rincian pembelian" })).toContainText("E2E Latte");
    await expect(guest.getByText("35.000", { exact: false }).last()).toBeVisible();
    await guest.goto("/visit/orders/" + orderId);

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
    await expect(waiter.getByText("RETURN_PENDING", { exact: true })).toBeVisible();
    // Damaged physical return removes the unit from circulation without fee.
    await waiter.getByLabel("Kode unit rusak/tidak lengkap").fill("UNO-E2E-001");
    await waiter.getByLabel("Temuan pemeriksaan (wajib)").fill("Dua kartu terlipat");
    await waiter.getByRole("checkbox", {
      name: /Unit fisik sudah diterima dan harus dikarantina/,
    }).check();
    await waiter.getByRole("button", { name: "Terima & karantina unit" }).click();
    await expect(waiter.getByText("Belum ada pinjaman aktif")).toBeVisible();
    await guest.goto("/visit/games");
    await expect(guest.getByText("Sudah dikembalikan")).toBeVisible();
    await expect(guest.getByText("0 unit tersedia saat terakhir diperiksa")).toBeVisible();
    // The manager-only landing alert uses the existing authenticated game
    // stream and intentionally excludes private complaint notes.
    const manager = await staff("manager");
    const managerHome=await manager.context().newPage();
    await managerHome.goto("/staff");
    await expect(managerHome.getByRole("heading", {
      name:"Pemeriksaan laporan kehilangan",
    })).toBeVisible();
    await expect(managerHome.getByText(
      "Tidak ada laporan kehilangan yang menunggu pemeriksaan.",
    )).toBeVisible();
    await expect(managerHome.getByText("Live · otomatis")).toBeVisible();
    const cashierHome=await cashier.context().newPage();
    await cashierHome.goto("/staff");
    await expect(cashierHome.getByRole("heading",{
      name:"Pemeriksaan laporan kehilangan",
    })).toHaveCount(0);
    await cashierHome.close();
    const waiterHome=await waiter.context().newPage();
    await waiterHome.goto("/staff");
    await expect(waiterHome.getByRole("heading",{
      name:"Pemeriksaan laporan kehilangan",
    })).toHaveCount(0);
    await waiterHome.close();
    // Staff directory is available to a manager, never to a cashier.
    const teamTab=await manager.context().newPage();
    await teamTab.goto("/staff/team");
    await expect(teamTab.getByRole("heading",{name:"Manajemen staf"})).toBeVisible();
    await expect(teamTab.getByText("e2e-waiter",{exact:true})).toBeVisible();
    const cashierTeam=await cashier.context().newPage();
    const deniedTeam=await cashierTeam.goto("/staff/team");
    expect(deniedTeam?.status()).toBe(404);
    await cashierTeam.close();
    await expect(teamTab.getByRole("heading",{name:"Tambah akun staf"})).toHaveCount(0);
    await teamTab.close();
    const admin=await staff("admin");
    const adminTeam=await admin.context().newPage();
    await adminTeam.goto("/staff/team");
    await expect(adminTeam.getByRole("heading",{name:"Tambah akun staf"})).toBeVisible();
    await adminTeam.getByLabel("Nama pengguna baru").fill("e2e-new-waiter");
    await adminTeam.getByLabel("Peran awal").selectOption("WAITER");
    await adminTeam.getByLabel(/Kata sandi awal/).fill("e2e-Unique-Staff-Password-2026");
    await adminTeam.getByLabel("Alasan pembuatan akun").fill("Synthetic E2E onboarding");
    await adminTeam.getByRole("checkbox",{
      name:/Saya telah memverifikasi kebutuhan akun/,
    }).check();
    await adminTeam.getByRole("button",{name:"Buat akun nonaktif"}).click();
    await expect(adminTeam).toHaveURL(/notice=created/);
    const newStaffCard=adminTeam.locator("article").filter({
      has:adminTeam.getByRole("heading",{name:"e2e-new-waiter"}),
    });
    await expect(newStaffCard).toContainText("NONAKTIF");
    const newStaffCtx=await makeContext();
    const newStaffPage=await newStaffCtx.newPage();
    await newStaffPage.goto("/staff/login");
    await newStaffPage.getByLabel("Nama pengguna").fill("e2e-new-waiter");
    await newStaffPage.getByLabel("Kata sandi").fill("e2e-Unique-Staff-Password-2026");
    await newStaffPage.getByRole("button",{name:"Masuk",exact:true}).click();
    await expect(newStaffPage).toHaveURL(/\/staff\/login\?status=invalid/);
    await newStaffCard.getByLabel("Alasan perubahan (wajib)").fill("Verified E2E activation");
    await newStaffCard.getByRole("checkbox",{
      name:/Saya telah memverifikasi akun/,
    }).check();
    await newStaffCard.getByRole("button",{name:"Aktifkan akses"}).click();
    await expect(adminTeam).toHaveURL(/notice=updated/);
    await newStaffPage.goto("/staff/login");
    await newStaffPage.getByLabel("Nama pengguna").fill("e2e-new-waiter");
    await newStaffPage.getByLabel("Kata sandi").fill("e2e-Unique-Staff-Password-2026");
    await newStaffPage.getByRole("button",{name:"Masuk",exact:true}).click();
    await expect(newStaffPage).toHaveURL(/\/staff$/);
    await expect(newStaffPage.getByText(/WAITER/)).toBeVisible();
    await adminTeam.close();
    await manager.goto("/staff/games");
    const quarantinedUnit=manager.locator("article").filter({
      has: manager.getByRole("heading", { name: "UNO Demo · UNO-E2E-001" }),
    });
    await expect(quarantinedUnit).toContainText("DAMAGED");
    await quarantinedUnit.getByLabel("Catatan pemeriksaan ulang")
      .fill("Kartu sudah diganti dan dihitung ulang");
    await quarantinedUnit.getByRole("checkbox", {
      name: /Saya sudah memeriksa langsung dan unit layak dipinjam lagi/,
    }).check();
    await quarantinedUnit.getByRole("button", { name: "Aktifkan kembali" }).click();
    await expect(manager.locator("article").filter({
      has: manager.getByRole("heading", { name: "UNO Demo · UNO-E2E-001" }),
    })).toContainText("AVAILABLE");
    // Guest page stayed open in a separate browser context; no manual
    // reload is permitted after manager restores the only physical copy.
    await expect(guest.getByText("1 unit tersedia saat terakhir diperiksa"))
      .toBeVisible({ timeout: 25000 });
    await expect(manager.getByRole("heading", { name: "Riwayat pemeriksaan unit" })).toBeVisible();
    await expect(manager.getByText("Catatan: Dua kartu terlipat")).toBeVisible();
    await expect(manager.getByText("Catatan: Kartu sudah diganti dan dihitung ulang")).toBeVisible();
    // Audit history search is independent from the inventory stock search.
    await manager.getByLabel("Cari catatan, staf, nama game atau kode unit")
      .fill("Dua kartu terlipat");
    await manager.getByRole("button", { name: "Cari riwayat" }).click();
    await expect(manager.getByText("Catatan: Dua kartu terlipat")).toBeVisible();
    await expect(manager.getByText("Catatan: Kartu sudah diganti dan dihitung ulang"))
      .toHaveCount(0);
    await manager.getByLabel("Filter hasil pemeriksaan").selectOption("AVAILABLE");
    await manager.getByRole("button", { name: "Cari riwayat" }).click();
    await expect(manager.getByText("Riwayat pemeriksaan tidak ditemukan.")).toBeVisible();
    await manager.getByRole("link", { name: "Reset riwayat" }).click();
    await expect(manager.getByText("Catatan: Dua kartu terlipat")).toBeVisible();
    await expect(manager.getByText("Catatan: Kartu sudah diganti dan dihitung ulang"))
      .toBeVisible();
    // Report a missing copy during another synthetic game loan only through
    // the existing staff-confirmed workflow. The in-place manager section
    // remains read-only until a report exists; no auto loss or fee is created.
    await expect(manager.getByRole("heading", { name: "Laporan unit belum ditemukan" }))
      .toBeVisible();
    await expect(manager.getByText("Tidak ada laporan unit hilang.")).toBeVisible();
    // Manager search is a DB-backed GET, not client-filtered first 250 copies.
    await manager.getByLabel("Cari unit atau permainan").fill("NO-SUCH-E2E-UNIT");
    await manager.getByRole("button", { name: "Cari unit" }).click();
    await expect(manager.getByText("Tidak ada unit yang sesuai.")).toBeVisible();
    await manager.getByRole("link", { name: "Reset pencarian" }).click();
    await expect(manager.getByRole("heading", { name: "UNO Demo · UNO-E2E-001" }))
      .toBeVisible();
    await manager.getByLabel("Filter kondisi unit").selectOption("DAMAGED");
    await manager.getByRole("button", { name: "Cari unit" }).click();
    await expect(manager.getByText("Tidak ada unit yang sesuai.")).toBeVisible();
    await manager.getByRole("link", { name: "Reset pencarian" }).click();

    // Manager-reviewed missing unit: borrowing again is permitted after the
    // previous verified return, but the replacement cannot be called returned
    // without manager investigation. This is deliberately non-financial.
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
    await waiter.getByLabel("Laporan unit belum ditemukan")
      .fill("UNO deck missing during staff delivery check");
    await waiter.getByRole("checkbox", {
      name: /Ini hanya laporan. Unit belum dikembalikan/,
    }).check();
    await waiter.getByRole("button", { name: "Laporkan unit hilang" }).click();
    await expect(manager.getByText(
      "Laporan staf: UNO deck missing during staff delivery check",
    )).toBeVisible({ timeout: 25000 });
    await managerHome.bringToFront();
    await expect(managerHome.getByText(
      "1 laporan kehilangan memerlukan pemeriksaan manager.",
    )).toBeVisible({ timeout:25000 });
    await expect(managerHome.getByText("UNO-E2E-001")).toBeVisible();
    await expect(managerHome.getByText("UNO deck missing during staff delivery check"))
      .toHaveCount(0);
    await expect(managerHome.getByRole("link",{
      name:"Buka laporan menunggu pemeriksaan",
    })).toHaveAttribute("href","/staff/games?istatus=OPEN#loss-incidents");
    await manager.bringToFront();
    // OPEN queue count is independent from manager filters; incident search
    // reaches the database and preserves copy/audit selections.
    await expect(manager.getByText("1 laporan kehilangan menunggu pemeriksaan manager."))
      .toBeVisible();
    await manager.getByLabel("Cari laporan berdasarkan unit, game, meja, pelapor atau catatan")
      .fill("NO-SUCH-LOSS-E2E-REPORT");
    await manager.getByRole("button", { name: "Cari laporan" }).click();
    await expect(manager.getByText("Tidak ada laporan yang sesuai.")).toBeVisible();
    await expect(manager.getByText("1 laporan kehilangan menunggu pemeriksaan manager."))
      .toBeVisible();
    await manager.getByRole("link", { name: "Reset laporan" }).click();
    await expect(manager.getByText(
      "Laporan staf: UNO deck missing during staff delivery check",
    )).toBeVisible();
    await manager.getByLabel("Filter status laporan").selectOption("DISMISSED");
    await manager.getByRole("button", { name: "Cari laporan" }).click();
    await expect(manager.getByText("Tidak ada laporan yang sesuai.")).toBeVisible();
    await manager.getByRole("link", { name: "Reset laporan" }).click();
    await expect(manager.getByText(
      "Laporan staf: UNO deck missing during staff delivery check",
    )).toBeVisible();
    // Manager-only evidence page exposes attributed server-side audit.
    const auditLink=manager.getByRole("link",{
      name:"Lihat jejak aktivitas staf",
    });
    const evidenceHref=await auditLink.getAttribute("href");
    expect(evidenceHref).toMatch(/^\/staff\/games\/incidents\/[0-9a-f-]{36}$/);
    const deniedWaiterTab=await waiter.context().newPage();
    const waiterEvidence=await deniedWaiterTab.goto(evidenceHref);
    expect(waiterEvidence?.status()).toBe(404);
    await deniedWaiterTab.close();
    const deniedCashierTab=await cashier.context().newPage();
    const cashierEvidence=await deniedCashierTab.goto(evidenceHref);
    expect(cashierEvidence?.status()).toBe(404);
    await deniedCashierTab.close();
    // The original waiter and cashier tabs must keep their existing
    // authenticated live subscriptions and operational page state.
    await auditLink.click();
    await expect(manager.getByRole("heading",{name:"Riwayat laporan unit"})).toBeVisible();
    // Audit correlation identifiers must wrap on a 320px phone, and the
    // read-only timeline must preserve accessible event chronology.
    await manager.setViewportSize({width:320,height:740});
    await expect(manager.locator("ol[aria-label='Urutan aktivitas staf']")).toBeVisible();
    await expect.poll(async()=>manager.evaluate(
      ()=>document.documentElement.scrollWidth-window.innerWidth
    )).toBeLessThanOrEqual(1);
    await expect(manager.locator("ol time[datetime]")).toHaveCount(1);
    await manager.setViewportSize({width:1280,height:720});
    await expect(manager.getByText("Staf: e2e-waiter")).toBeVisible();
    await expect(manager.getByText("Staf melaporkan unit belum ditemukan")).toBeVisible();
    await expect(manager.getByText("Belum ada keputusan manager untuk laporan ini."))
      .toBeVisible();
    await manager.getByRole("link",{name:"Kembali ke daftar laporan"}).click();
    await expect(manager.getByText("Laporan staf: UNO deck missing during staff delivery check"))
      .toBeVisible();
    await manager.getByLabel("Verifikasi kode unit yang hilang").fill("UNO-E2E-001");
    await manager.getByLabel("Alasan konfirmasi kehilangan")
      .fill("Counted all game units and verified this serial is missing");
    await manager.getByRole("checkbox", {
      name: /Sudah verifikasi unit fisik, saya setuju menandai copy LOST/,
    }).check();
    await manager.getByRole("button", { name: "Konfirmasi unit hilang" }).click();
    await expect(manager.getByText("Status laporan: CONFIRMED_LOST")).toBeVisible();
    await manager.getByRole("link",{name:"Lihat jejak aktivitas staf"}).click();
    await expect(manager.getByText("Manager mengonfirmasi kehilangan setelah pemeriksaan"))
      .toBeVisible();
    await expect(manager.getByText("Staf: e2e-manager")).toBeVisible();
    await expect(manager.getByText("Staf melaporkan unit belum ditemukan")).toBeVisible();
    await expect(manager.getByText("Staf: e2e-waiter")).toBeVisible();
    await expect(manager.getByText("Staf: e2e-manager")).toBeVisible();
    await manager.getByRole("link",{name:"Kembali ke daftar laporan"}).click();
    await managerHome.bringToFront();
    await expect(managerHome.getByText(
      "Tidak ada laporan kehilangan yang menunggu pemeriksaan.",
    )).toBeVisible({ timeout:25000 });
    await expect(managerHome.getByText(
      "1 laporan kehilangan memerlukan pemeriksaan manager.",
    )).toHaveCount(0);
    await manager.bringToFront();
    await expect(manager.locator("article").filter({
      has: manager.getByRole("heading", { name: "UNO Demo · UNO-E2E-001" }),
    })).toContainText("LOST");
    await expect(guest.getByText("Dilaporkan hilang dan dikonfirmasi oleh pengelola"))
      .toBeVisible({ timeout: 25000 });
    await expect(guest.getByText("0 unit tersedia saat terakhir diperiksa"))
      .toBeVisible({ timeout: 25000 });
    await expect(waiter.getByText("Belum ada pinjaman aktif"))
      .toBeVisible({ timeout: 25000 });
    // Recovered LOST units never bypass quarantine. The confirmed incident
    // and historical guest LOST loan stay recorded even after restocking.
    const lostCard=manager.locator("article").filter({
      has: manager.getByRole("heading", { name: "UNO Demo · UNO-E2E-001" }),
    });
    await lostCard.getByLabel("Kode fisik unit yang ditemukan kembali")
      .fill("UNO-E2E-001");
    await lostCard.getByLabel("Catatan lokasi dan kondisi penemuan")
      .fill("Located beneath rear cabinet; requires inspection");
    await lostCard.getByRole("checkbox", {
      name: /Unit benar-benar ditemukan dan akan masuk karantina DAMAGED/,
    }).check();
    await lostCard.getByRole("button", { name: "Temukan & karantina unit" }).click();
    await expect(manager.locator("article").filter({
      has: manager.getByRole("heading", { name: "UNO Demo · UNO-E2E-001" }),
    })).toContainText("DAMAGED");
    await expect(guest.getByText("0 unit tersedia saat terakhir diperiksa"))
      .toBeVisible({ timeout: 25000 });
    await expect(guest.getByText("Dilaporkan hilang dan dikonfirmasi oleh pengelola"))
      .toBeVisible();
    const foundCard=manager.locator("article").filter({
      has: manager.getByRole("heading", { name: "UNO Demo · UNO-E2E-001" }),
    });
    await foundCard.getByLabel("Catatan pemeriksaan ulang")
      .fill("Verified serial and all UNO cards have been counted");
    await foundCard.getByRole("checkbox", {
      name: /Saya sudah memeriksa langsung dan unit layak dipinjam lagi/,
    }).check();
    await foundCard.getByRole("button", { name: "Aktifkan kembali" }).click();
    await expect(manager.locator("article").filter({
      has: manager.getByRole("heading", { name: "UNO Demo · UNO-E2E-001" }),
    })).toContainText("AVAILABLE");
    await expect(guest.getByText("1 unit tersedia saat terakhir diperiksa"))
      .toBeVisible({ timeout: 25000 });
    await expect(manager.getByText("Catatan: Located beneath rear cabinet; requires inspection"))
      .toBeVisible();
    await expect(manager.getByText("Catatan: Verified serial and all UNO cards have been counted"))
      .toBeVisible();
    await expect(manager.getByText("Status laporan: CONFIRMED_LOST")).toBeVisible();

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
