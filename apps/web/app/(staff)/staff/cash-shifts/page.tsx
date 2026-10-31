import { randomUUID } from "node:crypto";
import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import { canUseOcc, idr, localTime, staffIdentity } from "@/lib/staff-server";
import { cashShiftDashboard } from "@/lib/cash-shifts-server";
import { closeCashShift, openCashShift } from "./actions";

export const dynamic = "force-dynamic";

export default async function CashShiftsPage({
  searchParams,
}: { searchParams: Promise<{ notice?: string }> }) {
  const identity = await staffIdentity();
  if (identity.kind === "signed-out") redirect("/staff/login?status=expired");
  if (identity.kind === "unavailable") return <main className="staff-shell">
    <h1>Shift kasir tidak dapat diverifikasi</h1>
    <p>Server identitas tidak tersedia. Tidak ada kas yang diubah.</p>
    <Link href="/staff">Kembali</Link>
  </main>;
  if (!canUseOcc(identity.staff.roles)) notFound();
  const result = await cashShiftDashboard();
  if (!result.ok && result.status === 401) redirect("/staff/login?status=expired");
  const query = await searchParams;
  const active = result.ok ? result.data.active : null;
  const closeAllowed = active !== null && (identity.staff.id === active.openedBy ||
    identity.staff.roles.some((role) => role === "MANAGER" || role === "ADMIN"));

  return <main className="staff-shell">
    <header className="staff-header">
      <div><div className="staff-eyebrow">OPERASIONAL / CASHIER</div>
        <h1>Rekonsiliasi kas per shift</h1>
        <p className="staff-muted">Pencatatan uang laci kasir secara manual dengan jejak audit.</p>
      </div>
      <Link href="/staff/reports">Laporan harian</Link>
    </header>
    <nav className="staff-breadcrumb"><Link href="/staff">Ruang staf</Link>
      <span>/</span><strong>Shift kasir</strong></nav>
    {query.notice && <p className="staff-notice" role={query.notice === "opened" ||
        query.notice === "closed" ? "status" : "alert"}>
      {query.notice === "opened" ? "Shift berhasil dibuka." :
        query.notice === "closed" ? "Shift ditutup dan selisih dicatat sebagai audit." :
          query.notice === "conflict" ? "Status shift telah berubah. Periksa kembali." :
            query.notice === "forbidden" ? "Akses tindakan ditolak." :
              query.notice === "offline" ? "Server tidak tersedia, jangan mengulangi pencatatan tanpa memeriksa status." :
                "Input tidak valid. Periksa nominal dan konfirmasi."}
    </p>}
    {!result.ok && <section className="staff-panel" role="alert">
      Status kas tidak dapat dibaca dari database. Tidak boleh menganggap saldo nol.
    </section>}
    {result.ok && !active && <section className="staff-panel">
      <h2>Buka shift kasir</h2>
      <p>Belum ada shift terbuka. Hitung uang awal secara fisik sebelum memulai.</p>
      <form action={openCashShift} className="staff-form">
        <input type="hidden" name="idempotencyKey" value={randomUUID()}/>
        <label htmlFor="opening-float">Uang awal laci (IDR)</label>
        <input id="opening-float" type="number" name="openingFloatIdr"
          min="0" max="1000000000000" step="1" required/>
        <label className="staff-check">
          <input type="checkbox" name="confirm" value="yes" required/>
          Uang awal telah dihitung langsung
        </label>
        <button type="submit" className="staff-primary">Buka shift</button>
      </form>
    </section>}
    {result.ok && active && <section className="staff-panel" aria-label="Shift kasir aktif">
      <h2>Shift aktif</h2>
      <div className="staff-info-grid">
        <div><small>Mulai (WIB)</small><strong>{localTime(active.openedAt)}</strong></div>
        <div><small>Uang awal</small><strong>{idr(active.openingFloatIdr)}</strong></div>
        <div><small>Cash tercatat selama shift</small><strong>{idr(active.recordedCashIdr)}</strong></div>
        <div><small>Perkiraan uang laci</small><strong>{idr(active.expectedDrawerIdr)}</strong></div>
      </div>
      <p className="staff-muted">Perkiraan dihitung dari catatan cash dikurangi kembalian.
        Jika waiter belum menyerahkan uang, nominal fisik dapat berbeda.
        Angka dapat berubah selama pesanan cash dicatat.</p>
      {closeAllowed ? <form action={closeCashShift} className="staff-form">
        <h3>Tutup dan hitung shift</h3>
        <input type="hidden" name="shiftId" value={active.id}/>
        <input type="hidden" name="idempotencyKey" value={"close-" + active.id}/>
        <label htmlFor="counted-cash">Uang fisik yang dihitung (IDR)</label>
        <input id="counted-cash" type="number" name="countedCashIdr"
          min="0" max="1000000000000" step="1" required/>
        <label htmlFor="closing-reason">Catatan pemeriksaan</label>
        <textarea id="closing-reason" name="reason" minLength={5} maxLength={300}
          rows={3} required placeholder="Contoh: dihitung bersama kasir, uang waiter sudah diterima"/>
        <label className="staff-check">
          <input type="checkbox" name="confirm" value="yes" required/>
          Saya sudah menghitung uang fisik dan menyetujui pencatatan selisih
        </label>
        <button type="submit" className="staff-primary">Tutup shift &amp; catat selisih</button>
      </form> : <p>Hanya staf pembuka atau manager/admin yang boleh menutup shift ini.</p>}
    </section>}
    {result.ok && <section className="staff-panel" aria-label="Riwayat shift kasir">
      <h2>10 shift yang sudah ditutup</h2>
      {result.data.recent.length === 0 && <p role="status">Belum ada shift selesai.</p>}
      {result.data.recent.length > 0 && <div className="staff-table-wrap">
        <table className="staff-table">
          <thead><tr><th scope="col">Dibuka (WIB)</th>
            <th scope="col">Ditutup (WIB)</th><th scope="col">Seharusnya</th>
            <th scope="col">Dihitung</th><th scope="col">Selisih</th></tr></thead>
          <tbody>{result.data.recent.map((shift) => <tr key={shift.id}>
            <td>{localTime(shift.openedAt)}</td>
            <td>{shift.closedAt ? localTime(shift.closedAt) : "-"}</td>
            <td>{idr(shift.expectedDrawerIdr)}</td>
            <td>{idr(shift.countedCashIdr ?? 0)}</td>
            <td><strong>{idr(shift.differenceIdr ?? 0)}</strong></td>
          </tr>)}</tbody>
        </table>
      </div>}
    </section>}
    <p className="staff-footnote">Rekonsiliasi ini hanya catatan operasional sementara
      untuk satu laci kas dalam satu kafe. Tidak memverifikasi uang di bank,
      perpindahan kas antar staf, refund, ataupun saldo riil. Menutup shift
      tidak mengubah pesanan, status PAID, atau struk yang sudah terbit.
    </p>
  </main>;
}
