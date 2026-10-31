import { test, expect } from "@playwright/test";

// Runs ONLY on isolated disposable sandbox payment CI database.
const BASE=process.env.E2E_BASE_URL??"http://localhost:3000";
const PASSWORD="e2e-browser-only-secret-2026";

async function login(browser,role) {
  const ctx=await browser.newContext({
    baseURL:BASE,ignoreHTTPSErrors:process.env.E2E_PROXY_TLS==="true",
  });
  const page=await ctx.newPage();
  await page.goto("/staff/login");
  await page.getByLabel("Nama pengguna").fill("e2e-"+role);
  await page.getByLabel("Kata sandi").fill(PASSWORD);
  await page.getByRole("button",{name:"Masuk",exact:true}).click();
  await expect(page).toHaveURL(/\/staff$/);
  return {ctx,page};
}

test("sandbox reconciliation evidence is read-only and hidden from non-admin roles",
  async ({browser})=>{
    test.skip(process.env.E2E_SANDBOX_PAYMENTS!=="true",
      "Only the disposable, explicitly enabled payment sandbox is allowed.");
    const contexts=[];
    try {
      const admin=await login(browser,"admin");
      contexts.push(admin.ctx);
      await expect(admin.page.getByRole("heading",{
        name:"Rekonsiliasi pembayaran simulasi",
      })).toBeVisible();
      await admin.page.getByRole("link",{name:"Periksa event sandbox"}).click();
      await expect(admin.page).toHaveURL(/\/staff\/reconciliation$/);
      await expect(admin.page.getByRole("heading",{
        name:"Antrean bukti pembayaran simulasi",
      })).toBeVisible();
      await expect(admin.page.getByText(
        /Event SUCCEEDED di layar ini hanya klaim palsu/
      )).toBeVisible();
      const rows=admin.page.locator("tbody tr");
      await expect(rows).toHaveCount(1);
      await expect(rows.first()).toContainText("UNKNOWN_REFERENCE");
      await expect(rows.first()).toContainText("FAKE_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
      await expect(rows.first()).toContainText("Tidak diketahui");
      await expect(admin.page.getByText("Klaim sukses, belum diverifikasi")).toBeVisible();
      await expect(admin.page.getByRole("button",{name:"Tampilkan bukti"})).toBeVisible();
      await expect(admin.page.getByRole("button",{name:/setujui|lunas|refund|approve/i}))
        .toHaveCount(0);

      // Investigate with a controlled code; no FAKE claim can be approved.
      const form=rows.first().locator("form");
      await form.getByLabel("Tindak lanjut").selectOption("ESCALATE");
      await form.getByRole("checkbox").check();
      await form.getByRole("button",{name:"Catat investigasi"}).click();
      await expect(admin.page).toHaveURL(/notice=recorded/);
      await expect(admin.page.getByText(
        /Tindakan investigasi tersimpan sebagai jejak audit/
      )).toBeVisible();
      await expect(admin.page.locator("tbody tr").first()).toContainText("ESCALATE");
      await expect(admin.page.locator("tbody tr").first())
        .toContainText("UNKNOWN_REFERENCE");
      // A repeated same admin+event+code action replays the original audit id.
      const replayForm=admin.page.locator("tbody tr").first().locator("form");
      await replayForm.getByLabel("Tindak lanjut").selectOption("ESCALATE");
      await replayForm.getByRole("checkbox").check();
      await replayForm.getByRole("button",{name:"Catat investigasi"}).click();
      await expect(admin.page).toHaveURL(/notice=recorded/);
      await expect(admin.page.locator("tbody tr").first()).toContainText("ESCALATE");

      await admin.page.getByLabel("Filter klasifikasi").selectOption("FINANCIAL_MISMATCH");
      await admin.page.getByRole("button",{name:"Tampilkan bukti"}).click();
      await expect(admin.page).toHaveURL(/classification=FINANCIAL_MISMATCH/);
      await expect(admin.page.getByText(
        /Tidak ada event simulasi untuk halaman dan filter ini/
      )).toBeVisible();
      await expect(admin.page.locator("tbody tr")).toHaveCount(0);
      const bad=await admin.page.goto("/staff/reconciliation?classification=INVALID");
      expect(bad?.status()).toBe(404);
      const outOfRange=await admin.page.goto("/staff/reconciliation?page=1001");
      expect(outOfRange?.status()).toBe(404);

      for(const role of ["manager","cashier"]) {
        const other=await login(browser,role);
        contexts.push(other.ctx);
        await expect(other.page.getByRole("link",{name:"Periksa event sandbox"}))
          .toHaveCount(0);
        const denied=await other.page.goto("/staff/reconciliation");
        expect(denied?.status()).toBe(404);
      }
    } finally {
      await Promise.all(contexts.map((ctx)=>ctx.close()));
    }
});
