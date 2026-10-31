import { expect, test } from "@playwright/test";

// This file runs ONLY in a disposable sandbox CI job with the two flags ON.
// Never run with a merchant account, a production DB or a real payment host.
const BASE = process.env.E2E_BASE_URL ?? "http://localhost:3000";
const PASSWORD = "e2e-browser-only-secret-2026";
const ITEM = "cccccccc-cccc-4ccc-8ccc-cccccccc1111";
const API = "http://127.0.0.1:8080";

async function join(page, name, proof) {
  await page.goto("/t/e2e-01");
  await page.getByLabel("Nama panggilan").fill(name);
  await page.getByLabel("Kode bergabung dari waiter").fill(proof);
  await page.getByRole("button", { name: "Gabung dan lihat menu" }).click();
  await expect(page).toHaveURL(/\/visit$/);
}

async function addCartItem(page) {
  await page.goto("/visit/menu/" + ITEM);
  await expect(page.getByRole("heading", { name: "E2E Latte" })).toBeVisible();
  await page.getByRole("group", { name: /Ukuran/ })
    .getByRole("radio", { name: /Regular/ }).check();
  await page.getByLabel(/Jumlah porsi/).fill("1");
  await page.getByRole("button", { name: "Simpan ke keranjang" }).click();
  await expect(page).toHaveURL(/\/visit\/cart\?notice=updated/);
  await expect(page.getByRole("heading", { name: "Simulasi pembayaran digital (SANDBOX)" }))
    .toBeVisible();
  await expect(page.getByText(/QRIS\/VA di sini palsu/)).toBeVisible();
}

async function checkoutSandbox(page, method) {
  const form = page.locator("form").filter({
    has: page.getByRole("button", { name: "Simulasikan pesanan " + method }),
  });
  await form.getByRole("checkbox").check();
  await form.getByRole("button", { name: "Simulasikan pesanan " + method }).click();
  await expect(page).toHaveURL(/\/visit\/orders\/[0-9a-f-]{36}\?notice=sandbox-submitted/);
  const orderId = new URL(page.url()).pathname.split("/").at(-1);
  expect(orderId).toMatch(/^[0-9a-f-]{36}$/);
  await expect(page.getByText("Pembayaran (" + method + ")")).toBeVisible();
  await expect(page.getByText("PENDING", { exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Struk digital" })).toHaveCount(0);
  await expect(page.getByRole("heading", { name: "Simulasi " + method + " (SANDBOX)" }))
    .toBeVisible();
  await expect(page.getByText(/BUKAN METODE BAYAR NYATA/)).toBeVisible();
  return orderId;
}

test("guest QRIS and VA sandbox are non-payable, scoped, idempotent and never PAID", async ({ browser }) => {
  test.skip(process.env.E2E_SANDBOX_PAYMENTS !== "true",
    "Isolated sandbox payment browser job only; default cash jobs remain untouched.");
  test.setTimeout(150_000);
  const contexts = [];
  const options = { baseURL: BASE, ignoreHTTPSErrors: process.env.E2E_PROXY_TLS === "true" };
  async function context() {
    const instance = await browser.newContext(options);
    contexts.push(instance);
    return instance;
  }
  try {
    const waiterContext = await context();
    const waiter = await waiterContext.newPage();
    await waiter.goto("/staff/login");
    await waiter.getByLabel("Nama pengguna").fill("e2e-waiter");
    await waiter.getByLabel("Kata sandi").fill(PASSWORD);
    await waiter.getByRole("button", { name: "Masuk", exact: true }).click();
    await expect(waiter).toHaveURL(/\/staff$/);

    await waiter.goto("/staff/tables");
    const table = waiter.locator("article").filter({
      has: waiter.getByRole("heading", { name: "Meja E2E-01" }),
    });
    await expect(table).toContainText("TERSEDIA");
    await table.getByRole("checkbox").check();
    await table.getByRole("button", { name: "Buka kunjungan" }).click();
    const proofInput = waiter.getByLabel("Kode bergabung sementara");
    await expect(proofInput).toBeVisible();
    const proof = await proofInput.inputValue();
    expect(proof).toMatch(/^[A-Za-z0-9_-]{43}$/);

    const aliceContext = await context();
    const alice = await aliceContext.newPage();
    await join(alice, "Sandbox QRIS Guest", proof);
    const aliceCookie = (await aliceContext.cookies()).find((c) => c.name === "scs_guest_session");
    expect(aliceCookie).toMatchObject({ httpOnly: true, sameSite: "Strict", path: "/visit" });
    expect(await alice.evaluate(() => document.cookie.includes("scs_guest_session"))).toBe(false);
    await addCartItem(alice);
    const qrisOrder = await checkoutSandbox(alice, "QRIS");

    const createForm = alice.locator("form").filter({
      has: alice.getByRole("button", { name: "Buat referensi FAKE QRIS" }),
    });
    const createKey = await createForm.locator('input[name="idempotencyKey"]').inputValue();
    expect(createKey).toMatch(/^sandbox_[a-f0-9]{32}_initial$/);
    await createForm.getByRole("checkbox").check();
    await createForm.getByRole("button", { name: "Buat referensi FAKE QRIS" }).click();
    await expect(alice).toHaveURL(/notice=sandbox-attempt/);
    await expect(alice.getByText("FAKE_QRIS_NOT_PAYABLE")).toHaveCount(0);
    await expect(alice.getByText(/FAKE_[a-f0-9]{32}/)).toBeVisible();
    await expect(alice.getByText("PENDING", { exact: true })).toBeVisible();
    await expect(alice.getByRole("heading", { name: "Struk digital" })).toHaveCount(0);
    await expect(alice.getByRole("button", { name: "Buat referensi FAKE QRIS" })).toHaveCount(0);

    const token = aliceCookie.value;
    const ownedApi = API + "/api/v1/me/orders/" + qrisOrder + "/sandbox-attempts";
    const auth = { Authorization: "Bearer " + token };
    const storedResponse = await aliceContext.request.get(ownedApi, { headers: auth });
    expect(storedResponse.status()).toBe(200);
    expect(storedResponse.headers()["cache-control"]).toContain("no-store");
    const stored = (await storedResponse.json()).data;
    expect(stored).toHaveLength(1);
    expect(stored[0]).toMatchObject({
      method: "QRIS", state: "PENDING",
      nonPayableDisplayHint: "FAKE_QRIS_NOT_PAYABLE",
      environment: "SANDBOX_FAKE_NOT_MONEY",
    });
    expect(stored[0].providerReference).toMatch(/^FAKE_[a-f0-9]{32}$/);
    expect(stored[0].amountIdr).toBe(28000);
    expect(JSON.stringify(stored)).not.toContain(createKey);
    const replay = await aliceContext.request.post(ownedApi, {
      headers: { ...auth, "Idempotency-Key": createKey },
      data: { method: "QRIS" },
    });
    expect(replay.status(), await replay.text()).toBe(200);
    expect((await replay.json()).data.id).toBe(stored[0].id);
    const competing = await aliceContext.request.post(ownedApi, {
      headers: { ...auth, "Idempotency-Key": "sandbox_competing_key_0001" },
      data: { method: "QRIS" },
    });
    expect(competing.status()).toBe(409);
    const wrongMethod = await aliceContext.request.post(ownedApi, {
      headers: { ...auth, "Idempotency-Key": createKey },
      data: { method: "VA" },
    });
    expect(wrongMethod.status()).toBe(409);

    const qrisReceipt = await aliceContext.request.get(
      "/visit/orders/" + qrisOrder + "/receipt");
    expect(qrisReceipt.status()).toBe(404);
    const qrisDetail = await aliceContext.request.get(
      API + "/api/v1/me/orders/" + qrisOrder, { headers: auth });
    expect((await qrisDetail.json()).data).toMatchObject({
      paymentMethod: "QRIS", paymentStatus: "PENDING",
    });

    // Two guests share a visit but NEVER share order authorization.
    const bobContext = await context();
    const bob = await bobContext.newPage();
    await join(bob, "Sandbox VA Guest", proof);
    const bobCookie = (await bobContext.cookies()).find((c) => c.name === "scs_guest_session");
    const bobAuth = { Authorization: "Bearer " + bobCookie.value };
    expect((await bobContext.request.get(ownedApi, { headers: bobAuth })).status()).toBe(404);
    expect((await bobContext.request.post(ownedApi, {
      headers: { ...bobAuth, "Idempotency-Key": "sandbox_stranger_key_0001" },
      data: { method: "QRIS" },
    })).status()).toBe(404);
    await addCartItem(bob);
    const vaOrder = await checkoutSandbox(bob, "VA");
    const vaForm = bob.locator("form").filter({
      has: bob.getByRole("button", { name: "Buat referensi FAKE VA" }),
    });
    await vaForm.getByRole("checkbox").check();
    await vaForm.getByRole("button", { name: "Buat referensi FAKE VA" }).click();
    await expect(bob).toHaveURL(/notice=sandbox-attempt/);
    await expect(bob.getByText(/FAKE_[a-f0-9]{32}/)).toBeVisible();
    const vaApi = await bobContext.request.get(
      API + "/api/v1/me/orders/" + vaOrder + "/sandbox-attempts",
      { headers: bobAuth });
    expect(vaApi.status()).toBe(200);
    expect((await vaApi.json()).data[0]).toMatchObject({
      method: "VA", state: "PENDING",
      nonPayableDisplayHint: "FAKE_VA_NOT_PAYABLE",
      environment: "SANDBOX_FAKE_NOT_MONEY",
    });
    expect((await aliceContext.request.get(
      API + "/api/v1/me/orders/" + vaOrder + "/sandbox-attempts",
      { headers: auth })).status()).toBe(404);
    expect((await bobContext.request.get(
      "/visit/orders/" + vaOrder + "/receipt")).status()).toBe(404);

    // Staff cannot close a visit with unresolved simulated charges.
    await waiter.goto("/staff/tables");
    await table.getByRole("checkbox", {
      name: /Semua pesanan dan pembayaran telah selesai/,
    }).check();
    await table.getByRole("button", { name: "Tutup kunjungan" }).click();
    await expect(waiter.getByText(/masih ada pesanan\/pembayaran belum selesai/))
      .toBeVisible();
    await expect(table).toContainText("TERISI");
  } finally {
    await Promise.all(contexts.map((c) => c.close()));
  }
});
