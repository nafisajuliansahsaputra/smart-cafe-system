import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import { canUseOcc, dailyCashReport, idr, staffIdentity } from "@/lib/staff-server";

export const dynamic = "force-dynamic";

function todayWib(): string {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: "Asia/Jakarta", year: "numeric", month: "2-digit", day: "2-digit",
  }).formatToParts(new Date());
  const part = (kind: string) => parts.find((p) => p.type === kind)?.value ?? "";
  return part("year") + "-" + part("month") + "-" + part("day");
}

export default async function DailyCashPage({
  searchParams,
}: {
  searchParams: Promise<{ date?: string }>;
}) {
  const current = await staffIdentity();
  if (current.kind === "signed-out") redirect("/staff/login?status=expired");
  if (current.kind === "unavailable") {
    return <main className="staff-shell">
      <h1>Laporan belum dapat diverifikasi</h1>
      <p>Identitas staf tidak dapat diperiksa. Tidak ada total pemasukan yang diasumsikan.</p>
      <Link href="/staff/reports">Coba lagi</Link>
    </main>;
  }
  if (!canUseOcc(current.staff.roles)) notFound();
  const params = await searchParams;
  const date = params.date ?? todayWib();
  const validDate = typeof date === "string" && /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(date);
  const report = validDate ? await dailyCashReport(date) : null;
  if (report && !report.ok && report.status === 401) redirect("/staff/login?status=expired");

  return <main className="staff-shell">
    <header className="staff-header">
      <div>
        <div className="staff-eyebrow">OPERASIONAL / CASHIER</div>
        <h1>Laporan cash harian</h1>
        <p className="staff-muted">Penerimaan tunai berdasarkan waktu terbit struk (WIB), bukan order baru.</p>
      </div>
      <Link href="/staff">Ruang staf</Link>
    </header>
    <nav className="staff-breadcrumb"><Link href="/staff">Ruang staf</Link>
      <span>/</span><strong>Laporan cash</strong></nav>
    <section className="staff-panel">
      <h2>Pilih tanggal (WIB)</h2>
      <form method="GET" className="staff-form">
        <label htmlFor="daily-cash-date">Tanggal laporan</label>
        <input id="daily-cash-date" type="date" name="date"
          defaultValue={validDate ? date : todayWib()}
          min="2000-01-01" max="2100-12-31" required />
        <button type="submit" className="staff-primary">Tampilkan laporan</button>
      </form>
    </section>
    {!validDate && <p className="staff-notice" role="alert">
      Tanggal tidak valid. Gunakan format YYYY-MM-DD.
    </p>}
    {report && !report.ok && <p className="staff-notice" role="alert">
      {report.status === 400
        ? "Tanggal tidak valid. Pilih tanggal antara tahun 2000 sampai 2100."
        : "Laporan belum dapat dimuat. Data tidak akan diganti dengan perkiraan."}
    </p>}
    {report?.ok && <>
      <section className="staff-panel" aria-label="Ringkasan penerimaan cash">
        <h2>Ringkasan {report.data.date}</h2>
        <div className="staff-info-grid">
          <div><small>Pesanan cash lunas</small>
            <strong>{report.data.paidOrders.toLocaleString("id-ID")}</strong></div>
          <div><small>Total cash diterima (bruto)</small>
            <strong>{idr(report.data.grossCollectedIdr)}</strong></div>
          <div><small>Zona waktu</small><strong>WIB</strong></div>
        </div>
        {report.data.paidOrders === 0 &&
          <p role="status">Belum ada penerimaan cash untuk tanggal ini.</p>}
      </section>
      <section className="staff-panel">
        <h2>10 item terlaris berdasarkan jumlah terjual</h2>
        {report.data.topItems.length === 0 ? (
          <p>Tidak ada item lunas untuk tanggal ini.</p>
        ) : (
          <div className="staff-table-wrap"><table className="staff-table">
            <thead><tr><th scope="col">Nama item</th><th scope="col">Jumlah</th>
              <th scope="col">Penjualan bruto</th></tr></thead>
            <tbody>{report.data.topItems.map((item) => (
              <tr key={item.name}><td>{item.name}</td>
                <td>{item.quantity.toLocaleString("id-ID")}</td>
                <td>{idr(item.grossIdr)}</td></tr>
            ))}</tbody>
          </table></div>
        )}
      </section>
    </>}
    <p className="staff-footnote">
      Laporan hanya mencakup struk CASH yang diterbitkan server setelah pembayaran.
      Tidak termasuk QRIS, VA, order belum lunas, biaya operasional, atau penyesuaian refund.
      Total item tidak otomatis sama dengan tagihan bila ada diskon, pajak, atau biaya layanan.
      Ini bukan rekonsiliasi kas/bank.
    </p>
  </main>;
}
