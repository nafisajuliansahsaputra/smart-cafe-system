import { randomUUID } from "node:crypto";
import Link from "next/link";
import { LiveRefresh } from "@/components/live-refresh";
import { notFound, redirect } from "next/navigation";
import { getOrder, getOrderReceipt, getSandboxPaymentAttempts, guestIdentity, idr,
  sandboxCheckoutEnabled, timeWib, UUID_PATTERN } from "@/lib/guest-server";
import { createSandboxAttempt } from "../../actions";

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
  const sandbox = sandboxCheckoutEnabled();
  const digital = order.ok && order.data.paymentStatus === "PENDING" &&
    (order.data.paymentMethod === "QRIS" || order.data.paymentMethod === "VA");
  const attempts = sandbox && digital ? await getSandboxPaymentAttempts(id) : null;
  return (
    <main className="guest-shell">
      <header className="guest-top"><Link className="guest-wordmark" href="/visit">SMART CAFE</Link>
        <Link href="/visit/cart">Keranjang</Link></header>
      <nav className="guest-breadcrumb"><Link href="/visit/orders">Pesanan saya</Link><span>/</span><strong>Detail</strong></nav>
      <div className="guest-live-line"><LiveRefresh url={`/visit/live?topic=order&id=${id}`} /></div>
      {notice === "submitted" && <p className="guest-alert" role="status">Pesanan cash berhasil dicatat oleh server, belum dibayar.</p>}
      {notice === "sandbox-submitted" && <p className="guest-alert" role="status">Pesanan simulasi berhasil dicatat. Pembayaran PENDING, tidak ada uang diterima.</p>}
      {notice === "sandbox-attempt" && <p className="guest-alert" role="status">Referensi sandbox FAKE dicatat. Ini BUKAN bukti pembayaran.</p>}
      {notice === "conflict" && <p className="guest-alert" role="status">Sudah ada perubahan atau percobaan belum selesai. Periksa riwayat di bawah.</p>}
      {notice === "offline" && <p className="guest-alert" role="alert">Status server belum terverifikasi. Jangan menganggap pembayaran sukses.</p>}
      {!order.ok && <section className="guest-panel"><h1>Detail tidak dapat dimuat</h1>
        <p>Periksa koneksi dan coba lagi. Tidak ada status sukses yang diasumsikan.</p></section>}
      {order.ok && (
        <>
          <section className="guest-panel">
            <p className="guest-eyebrow">DETAIL PESANAN / {timeWib(order.data.createdAt)} WIB</p>
            <h1>{order.data.status}</h1>
            <div className="guest-order-status">
              <div><small>Persiapan / pengantaran</small><strong>{order.data.status}</strong></div>
              <div><small>Pembayaran ({order.data.paymentMethod})</small><strong>{order.data.paymentStatus}</strong></div>
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
          {digital && <section className="guest-panel">
            <h2>Simulasi {order.data.paymentMethod} (SANDBOX)</h2>
            <p className="guest-alert" role="status">
              BUKAN METODE BAYAR NYATA. Referensi FAKE tidak dapat dibayar,
              tidak ada dana masuk, dan pesanan tetap PENDING.
              Jangan memindai atau mentransfer uang dari halaman ini.
            </p>
            {!sandbox && <p>Checkout digital hanya tersedia di lingkungan sandbox.
              Tidak ada instruksi pembayaran yang dapat digunakan.</p>}
            {sandbox && attempts && !attempts.ok && <p role="alert">
              Riwayat percobaan belum bisa diverifikasi. Jangan menganggap pembayaran berhasil.
            </p>}
            {sandbox && attempts && attempts.ok && <>
              {attempts.data.length===0 && <p>Belum ada referensi pembayaran simulasi.</p>}
              {attempts.data.map((attempt)=>(
                <article className="guest-panel" key={attempt.id}>
                  <strong>{attempt.method} · {attempt.state} · NON-PAYABLE</strong>
                  <p className="guest-footnote">Referensi: {attempt.providerReference}</p>
                  <p>{idr(attempt.amountIdr)} · SANDBOX_FAKE_NOT_MONEY</p>
                  <p className="guest-footnote">
                    Batas waktu simulasi: {timeWib(attempt.expiresAt)} WIB.
                    Lewatnya batas waktu bukan bukti status akhir pembayaran.
                  </p>
                </article>
              ))}
              {!attempts.data.some((a)=>a.state==="CREATED"||a.state==="PENDING") &&
                order.data.status==="SUBMITTED" && <form action={createSandboxAttempt}
                  className="guest-form">
                  <input type="hidden" name="orderId" value={id}/>
                  <input type="hidden" name="paymentMethod" value={order.data.paymentMethod}/>
                  <input type="hidden" name="idempotencyKey" value={randomUUID()}/>
                  <label className="guest-check">
                    <input name="confirm" type="checkbox" value="yes" required/>
                    Saya paham referensi ini hanya simulasi dan tidak menerima pembayaran
                  </label>
                  <button className="guest-secondary" type="submit">
                    Buat referensi FAKE {order.data.paymentMethod}
                  </button>
                </form>}
              {attempts.data.some((a)=>a.state==="CREATED"||a.state==="PENDING") &&
                <p>Masih ada percobaan simulasi yang belum direkonsiliasi.
                  Referensi baru tidak dapat dibuat untuk menghindari percobaan ganda.</p>}
            </>}
          </section>}
          {order.data.paymentStatus === "PAID" && (
            <section className="guest-panel">
              <h2>Struk digital</h2>
              {receipt && receipt.ok ? (
                <>
                  <p>Struk #{receipt.data.number} · {timeWib(receipt.data.issuedAt)} WIB</p>
                  <p><strong>{idr(receipt.data.totalIdr)}</strong> · {receipt.data.currency}</p>
                  <p className="guest-footnote">Struk tersedia setelah pembayaran dikonfirmasi. Buka rincian untuk mencetak atau menyimpan PDF.</p>
                  <Link className="guest-secondary" href={"/visit/orders/" + id + "/receipt"}>Cetak atau simpan PDF</Link>
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
