import Link from "next/link";
import { LiveRefresh } from "@/components/live-refresh";
import { notFound, redirect } from "next/navigation";
import { approveCash, rejectCash, logoutStaff } from "../../actions";
import { canUseOcc, idr, localTime, orderDetail, staffIdentity } from "@/lib/staff-server";

export const dynamic = "force-dynamic";

const ORDER_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const messages: Record<string, string> = {
  approved: "Pesanan telah disetujui untuk dapur. Pembayaran masih menunggu penagihan cash.",
  rejected: "Pesanan ditolak dan alokasi stok dibebaskan sesuai aturan server.",
  reason: "Alasan penolakan wajib antara 4 dan 300 karakter.",
  confirm: "Konfirmasi pemeriksaan pesanan sebelum melanjutkan.",
  forbidden: "Aksi ditolak: akun ini tidak memiliki izin.",
  conflict: "Status pesanan berubah atau tindakan tidak diizinkan. Periksa status terbaru.",
  offline: "API sedang tidak tersedia. Belum ada perubahan pesanan yang dapat dikonfirmasi.",
  failed: "Perintah tidak berhasil. Status server tidak diubah oleh tampilan ini.",
};

export default async function OrderDetailPage({
  params,
  searchParams,
}: {
  params: Promise<{ id: string }>;
  searchParams: Promise<{ notice?: string }>;
}) {
  const current = await staffIdentity();
  if (current.kind === "signed-out") redirect("/staff/login?status=expired");
  if (current.kind === "unavailable") {
    return <main className="staff-shell"><h1>API tidak tersedia</h1><p>Detail tidak bisa diverifikasi. Tidak ada pesanan yang diubah.</p><Link href="/staff/orders">Kembali</Link></main>;
  }
  if (!canUseOcc(current.staff.roles)) notFound();
  const { id } = await params;
  if (!ORDER_ID.test(id)) notFound();
  const { notice } = await searchParams;
  const message = notice && Object.hasOwn(messages, notice) ? messages[notice] : undefined;
  const result = await orderDetail(id);
  if (!result.ok && result.status === 401) redirect("/staff/login?status=expired");
  if (!result.ok && result.status === 404) notFound();
  const order = result.ok ? result.data : null;
  const canDecide = order?.orderStatus === "SUBMITTED" && order.paymentStatus === "AWAITING_CASH";
  return (
    <main className="staff-shell staff-wide">
      <header className="staff-header">
        <div><div className="staff-eyebrow">ORDER CONTROL CENTER / DETAIL</div><h1>Detail pesanan</h1><p className="staff-muted staff-id">{id}</p></div>
        <form action={logoutStaff}><button className="staff-secondary" type="submit">Keluar</button></form>
      </header>
      <nav className="staff-breadcrumb"><Link href="/staff">Ruang staf</Link><span>/</span><Link href="/staff/orders">Antrean</Link><span>/</span><strong>Detail</strong></nav>
      <div className="staff-live-line"><LiveRefresh url={`/staff/live?topic=order&id=${id}`} /></div>
      {message && <p className="staff-notice" role="status">{message}</p>}
      {!order ? (
        <section className="staff-panel"><h2>Detail tidak dapat dimuat</h2><p>Periksa koneksi API dan izin staf. Tidak ada tindakan yang dilakukan.</p><Link href={`/staff/orders/${id}`}>Coba lagi</Link></section>
      ) : (
        <>
          <section className="staff-panel">
            <div className="staff-info-grid">
              <div><small>Meja</small><strong>{order.tableCode}</strong></div>
              <div><small>Dibuat (WIB)</small><strong>{localTime(order.createdAt)}</strong></div>
              <div><small>Status pesanan</small><strong>{order.orderStatus}</strong></div>
              <div><small>Status pembayaran</small><strong className={order.paymentStatus === "PAID" ? "staff-paid" : "staff-unpaid"}>{order.paymentStatus}</strong></div>
              <div><small>Metode</small><strong>{order.paymentMethod}</strong></div>
            </div>
            <h2>Item pesanan</h2>
            <div className="staff-table-wrap"><table className="staff-table">
              <thead><tr><th>Item</th><th>Jumlah</th><th>Harga / unit</th><th>Total</th></tr></thead>
              <tbody>{order.lines.map((line, index) => (
                <tr key={index}>
                  <td><strong>{line.name}</strong>{line.variantLabel && <div className="staff-muted">{line.variantLabel}</div>}{line.notes && <div className="staff-muted">Catatan: {line.notes}</div>}</td>
                  <td>{line.quantity}</td><td>{idr(line.unitPriceIdr)}</td><td>{idr(line.lineTotalIdr)}</td>
                </tr>
              ))}</tbody>
            </table></div>
            <div className="staff-totals">
              <div><span>Subtotal</span><strong>{idr(order.subtotalIdr)}</strong></div>
              <div><span>Pajak</span><strong>{idr(order.taxIdr)}</strong></div>
              <div><span>Biaya layanan</span><strong>{idr(order.serviceFeeIdr)}</strong></div>
              <div className="staff-grand"><span>Total</span><strong>{idr(order.totalIdr)}</strong></div>
            </div>
          </section>
          {canDecide && (
            <section className="staff-panel">
              <h2>Keputusan pesanan cash</h2>
              <p className="staff-muted">Tinjau pesanan dan keberadaan tamu sebelum menyetujui. Aksi diverifikasi ulang secara transaksional di backend.</p>
              <div className="staff-actions">
                <form action={approveCash} className="staff-reject-form">
                  <input type="hidden" name="orderId" value={id} />
                  <label className="staff-check"><input name="confirm" value="yes" type="checkbox" required /> Saya telah memeriksa pesanan dan meja tamu.</label>
                  <button className="staff-primary" type="submit">Setujui untuk dapur</button>
                </form>
                <form action={rejectCash} className="staff-reject-form">
                  <input type="hidden" name="orderId" value={id} />
                  <label htmlFor="reason">Alasan penolakan (wajib)</label>
                  <input id="reason" name="reason" minLength={4} maxLength={300} required placeholder="Contoh: item tidak tersedia" />
                  <label className="staff-check"><input name="confirm" value="yes" type="checkbox" required /> Saya yakin pesanan perlu ditolak.</label>
                  <button className="staff-danger" type="submit">Tolak pesanan</button>
                </form>
              </div>
            </section>
          )}
          {!canDecide && <p className="staff-muted">Tidak ada aksi persetujuan/penolakan untuk status ini. Pembayaran cash dicatat melalui alur penyerahan/penerimaan uang yang terpisah.</p>}
        </>
      )}
      <Link className="staff-text-link" href="/staff/orders">Kembali ke antrean</Link>
    </main>
  );
}
