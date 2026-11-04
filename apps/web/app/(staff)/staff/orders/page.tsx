import Link from "next/link";
import { LiveRefresh } from "@/components/live-refresh";
import { notFound, redirect } from "next/navigation";
import { logoutStaff } from "../actions";
import { canUseOcc, idr, localTime, staffIdentity } from "@/lib/staff-server";
import { occOrderPage, orderStatuses, paymentStatuses } from "@/lib/staff-order-pages";

export const dynamic = "force-dynamic";

export default async function OrdersPage({
  searchParams,
}: {
  searchParams: Promise<{ status?: string; paymentStatus?: string; tableCode?: string; page?: string }>;
}) {
  const current = await staffIdentity();
  if (current.kind === "signed-out") redirect("/staff/login?status=expired");
  if (current.kind === "unavailable") {
    return <main className="staff-shell"><h1>API tidak tersedia</h1><p>Antrean belum dapat diverifikasi. Tidak ada pesanan yang diubah.</p><Link href="/staff/orders">Coba lagi</Link></main>;
  }
  if (!canUseOcc(current.staff.roles)) notFound();
  const params = await searchParams;
  const status = typeof params.status === "string" ? params.status : "";
  const paymentStatus = typeof params.paymentStatus === "string" ? params.paymentStatus : "";
  const tableCode = typeof params.tableCode === "string" ? params.tableCode.trim() : "";
  const validPage = params.page === undefined || /^(0|[1-9][0-9]{0,3})$/.test(params.page);
  const page = validPage ? Number(params.page ?? "0") : -1;
  const queue = await occOrderPage(page, status, paymentStatus, tableCode);
  const pageUrl = (n: number) => "/staff/orders?" + new URLSearchParams({
    page: String(n),
    ...(status ? { status } : {}),
    ...(paymentStatus ? { paymentStatus } : {}),
    ...(tableCode ? { tableCode } : {}),
  }).toString();
  if (!queue.ok && queue.status === 401) redirect("/staff/login?status=expired");
  return (
    <main className="staff-shell staff-wide">
      <header className="staff-header">
        <div><div className="staff-eyebrow">OPERASIONAL / CASHIER</div><h1>Order Control Center</h1><p className="staff-muted">Daftar pesanan dari PostgreSQL, 25 per halaman. Filter dan navigasi tetap tersedia saat antrean ramai.</p></div>
        <form action={logoutStaff}><button className="staff-secondary" type="submit">Keluar</button></form>
      </header>
      <nav className="staff-breadcrumb"><Link href="/staff">Ruang staf</Link><span>/</span><strong>Antrean</strong></nav>
      <div className="staff-live-line"><LiveRefresh url={`/staff/live?topic=orders`} /></div>
      <section className="staff-panel">
        <h2>Filter pesanan</h2>
        <form method="GET" className="staff-filters">
          <label>Status pesanan<select name="status" defaultValue={status}>{orderStatuses.map((value) => <option key={value} value={value}>{value || "Semua status"}</option>)}</select></label>
          <label>Pembayaran<select name="paymentStatus" defaultValue={paymentStatus}>{paymentStatuses.map((value) => <option key={value} value={value}>{value || "Semua pembayaran"}</option>)}</select></label>
          <label>Kode meja<input name="tableCode" defaultValue={tableCode} maxLength={40} placeholder="Contoh: 01" /></label>
          <button className="staff-primary" type="submit">Terapkan filter</button>
          <Link className="staff-text-link" href="/staff/orders">Reset</Link>
        </form>
      </section>
      <section className="staff-panel">
        <div className="staff-header staff-panel-header">
          <div><h2>Pesanan</h2><p className="staff-muted">Persetujuan cash hanya mengirim pesanan ke dapur, bukan menandai PAID.</p></div>
          <form method="GET">
            {status && <input type="hidden" name="status" value={status} />}
            {paymentStatus && <input type="hidden" name="paymentStatus" value={paymentStatus} />}
            {tableCode && <input type="hidden" name="tableCode" value={tableCode} />}
            {page > 0 && <input type="hidden" name="page" value={String(page)} />}
            <button className="staff-secondary" type="submit">Segarkan</button>
          </form>
        </div>
        {queue.ok && queue.data.items.length === 0 && <p role="status">Tidak ada pesanan pada halaman atau filter ini.</p>}
        {!queue.ok && (
          <p className="staff-notice" role="alert">
            {queue.status === 400 ? "Filter atau halaman tidak valid. Periksa pilihan pencarian."
              : queue.status === 403 ? "Akses ditolak oleh API."
              : "Antrean belum bisa dimuat. Periksa koneksi API lalu coba lagi."}
          </p>
        )}
        {queue.ok && queue.data.items.length > 0 && (
          <div className="staff-table-wrap"><table className="staff-table">
            <thead><tr><th>Waktu (WIB)</th><th>Meja</th><th>Pesanan</th><th>Pembayaran</th><th>Total</th><th></th></tr></thead>
            <tbody>{queue.data.items.map((order) => (
              <tr key={order.id}>
                <td>{localTime(order.createdAt)}</td>
                <td>{order.tableCode}</td>
                <td><span className="staff-status">{order.orderStatus}</span></td>
                <td><strong className={order.paymentStatus === "PAID" ? "staff-paid" : "staff-unpaid"}>{order.paymentStatus}</strong></td>
                <td>{idr(order.totalIdr)}</td>
                <td><Link href={`/staff/orders/${order.id}`}>Detail</Link></td>
              </tr>
            ))}</tbody>
          </table></div>
        )}
        {queue.ok && (
          <nav aria-label="Halaman antrean pesanan" className="staff-breadcrumb">
            {queue.data.page > 0 && <Link className="staff-link-button" href={pageUrl(queue.data.page - 1)}>
              Halaman sebelumnya
            </Link>}
            <span role="status">{queue.data.total} pesanan cocok · halaman {queue.data.page + 1} · 25 per halaman</span>
            {queue.data.hasNext && queue.data.page < 1000 &&
              <Link className="staff-link-button" href={pageUrl(queue.data.page + 1)}>
                Halaman berikutnya
              </Link>}
          </nav>
        )}
      </section>
      <p className="staff-footnote">Antrean diperiksa otomatis. Tombol Segarkan tetap tersedia saat koneksi terputus. Tidak ada pembayaran sungguhan dalam demo ini.</p>
    </main>
  );
}
