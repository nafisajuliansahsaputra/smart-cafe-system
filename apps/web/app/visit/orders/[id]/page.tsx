import Link from "next/link";
import { LiveRefresh } from "@/components/live-refresh";
import { notFound, redirect } from "next/navigation";
import { getOrder, getOrderReceipt, guestIdentity, idr, timeWib, UUID_PATTERN } from "@/lib/guest-server";

export const dynamic = "force-dynamic";
export default async function OrderDetailPage({ params, searchParams }: {
  params: Promise<{ id: string }>;
  searchParams: Promise<{ notice?: string }>;
}) {
  const identity = await guestIdentity();
  if (identity.kind === "signed-out") redirect("/visit");
  if (identity.kind === "unavailable") return (
    <main className="guest-shell"><h1>Detail belum dapat diperiksa</h1><p>Identitas tamu belum terverifikasi.</p></main>
  );
  const { id } = await params;
  if (!UUID_PATTERN.test(id)) notFound();
  const { notice } = await searchParams;
  const order = await getOrder(id);
  if (!order.ok && order.status === 404) notFound();
  if (!order.ok && order.status === 401) redirect("/visit");
  const receipt = order.ok && order.data.paymentStatus === "PAID" ? await getOrderReceipt(id) : null;
  return (
    <main className="guest-shell">
      <header className="guest-top"><Link className="guest-wordmark" href="/visit">SMART CAFE</Link>
        <Link href="/visit/cart">Keranjang</Link></header>
      <nav className="guest-breadcrumb"><Link href="/visit/orders">Pesanan saya</Link><span>/</span><strong>Detail</strong></nav>
      <div className="guest-live-line"><LiveRefresh url={`/visit/live?topic=order&id=${id}`} /></div>
      {notice === "submitted" && <p className="guest-alert" role="status">Pesanan berhasil dicatat oleh server. Menunggu persetujuan cash dari staf, belum dibayar.</p>}
      {!order.ok && <section className="guest-panel"><h1>Detail tidak dapat dimuat</h1>
        <p>Periksa koneksi dan coba lagi. Tidak ada status sukses yang diasumsikan.</p></section>}
      {order.ok && (
        <>
          <section className="guest-panel">
            <p className="guest-eyebrow">DETAIL PESANAN / {timeWib(order.data.createdAt)} WIB</p>
            <h1>{order.data.status}</h1>
            <div className="guest-order-status">
              <div><small>Persiapan / pengantaran</small><strong>{order.data.status}</strong></div>
              <div><small>Pembayaran</small><strong>{order.data.paymentStatus}</strong></div>
            </div>
            {order.data.paymentStatus === "AWAITING_CASH" && <p className="guest-alert">
              Belum lunas. Siapkan pembayaran cash saat diantar, jangan bayar dari tangkapan layar atau tautan tidak resmi.</p>}
            {order.data.status === "REJECTED" && <p className="guest-alert">Pesanan ditolak oleh staf sebelum diproses dapur. Kembali ke menu untuk memilih ulang.</p>}
            <h2>Item pesanan</h2>
            <ul className="guest-item-list">{order.data.lines.map((line,index) => (
              <li key={index}><div><strong>{line.quantity} × {line.name}</strong>
                {line.variantLabel && <small>{line.variantLabel}</small>}
                {line.notes && <small>Catatan: {line.notes}</small>}</div>
                <strong>{idr(line.lineTotalIdr)}</strong></li>
            ))}</ul>
            <div className="guest-cart-total"><span>Total final</span><strong>{idr(order.data.totalIdr)}</strong></div>
          </section>
          {order.data.paymentStatus === "PAID" && (
            <section className="guest-panel">
              <h2>Struk digital</h2>
              {receipt && receipt.ok ? (
                <>
                  <p>Struk #{receipt.data.number} · {timeWib(receipt.data.issuedAt)} WIB</p>
                  <p><strong>{idr(receipt.data.totalIdr)}</strong> · {receipt.data.currency}</p>
                  <p className="guest-footnote">Struk tersedia setelah pembayaran dikonfirmasi. Gunakan fitur cetak browser untuk menyimpan PDF.</p>
                </>
              ) : (
                <p role="alert">Pembayaran tercatat, tetapi struk belum dapat diverifikasi. Silakan muat ulang.</p>
              )}
            </section>
          )}
          <form method="GET"><button className="guest-secondary" type="submit">Segarkan status</button></form>
        </>
      )}
    </main>
  );
}
