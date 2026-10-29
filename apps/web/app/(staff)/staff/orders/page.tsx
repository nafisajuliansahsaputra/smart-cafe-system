import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import { logoutStaff } from "../actions";
import { canUseOcc, idr, localTime, orderQueue, staffIdentity } from "@/lib/staff-server";

export const dynamic = "force-dynamic";

const orderStatuses = ["", "SUBMITTED", "ACCEPTED", "PREPARING", "READY", "SERVED", "REJECTED", "CANCELLED"];
const paymentStatuses = ["", "AWAITING_CASH", "UNPAID", "PENDING", "PAID", "REFUNDED", "DISPUTED"];

export default async function OrdersPage({
  searchParams,
}: {
  searchParams: Promise<{ status?: string; paymentStatus?: string; tableCode?: string; notice?: string }>;
}) {
  const current = await staffIdentity();
  if (current.kind === "signed-out") redirect("/staff/login?status=expired");
  if (current.kind === "unavailable") {
    return <main className="staff-shell"><h1>API tidak tersedia</h1><p>Antrean belum dapat diverifikasi. Tidak ada pesanan yang diubah.</p><Link href="/staff/orders">Coba lagi</Link></main>;
  }
  if (!canUseOcc(current.staff.roles)) notFound();
  const params = await searchParams;
  const status = orderStatuses.includes(params.status ?? "") ? params.status ?? "" : "";
  const paymentStatus = paymentStatuses.includes(params.paymentStatus ?? "") ? params.paymentStatus ?? "" : "";
  const tableCode = typeof params.tableCode === "string" ? params.tableCode.trim().slice(0, 40) : "";
  const filters = new URLSearchParams();
  if (status) filters.set("status", status);
  if (paymentStatus) filters.set("paymentStatus", paymentStatus);
  if (tableCode) filters.set("tableCode", tableCode);
  const queue = await orderQueue(filters);
  if (!queue.ok && queue.status === 401) redirect("/staff/login?status=expired");
  return (
    <main className="staff-shell staff-wide">
      <header className="staff-header">
        <div><div className="staff-eyebrow">OPERASIONAL / CASHIER</div><h1>Order Control Center</h1><p className="staff-muted">Antrean langsung dari server, maksimal 100 pesanan terbaru. Segarkan untuk mengambil snapshot baru.</p></div>
        <form action={logoutStaff}><button className="staff-secondary" type="submit">Keluar</button></form>
      </header>
      <nav className="staff-breadcrumb"><Link href="/staff">Ruang staf</Link><span>/</span><strong>Antrean</strong></nav>
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
          <Link className="staff-link-button" href={`/staff/orders${filters.size ? `?${filters}` : ""}`}>Segarkan</Link>
        </div>
        {queue.ok && queue.data.length === 0 && <p role="status">Tidak ada pesanan untuk filter ini.</p>}
        {!queue.ok && (
          <p className="staff-notice" role="alert">
            {queue.status === 403 ? "Akses ditolak oleh API." : "Antrean belum bisa dimuat. Periksa koneksi API lalu coba lagi."}
          </p>
        )}
        {queue.ok && queue.data.length > 0 && (
          <div className="staff-table-wrap"><table className="staff-table">
            <thead><tr><th>Waktu (WIB)</th><th>Meja</th><th>Pesanan</th><th>Pembayaran</th><th>Total</th><th></th></tr></thead>
            <tbody>{queue.data.map((order) => (
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
      </section>
      <p className="staff-footnote">Belum realtime: data diperbarui saat halaman dimuat atau tombol Segarkan ditekan. Tidak ada pembayaran sungguhan dalam demo ini.</p>
    </main>
  );
}
