import Link from "next/link";
import { LiveRefresh } from "@/components/live-refresh";
import { redirect } from "next/navigation";
import { getOrders, guestIdentity, idr } from "@/lib/guest-server";

export const dynamic = "force-dynamic";

export default async function OrdersPage() {
  const identity = await guestIdentity();
  if (identity.kind === "signed-out") redirect("/visit");
  if (identity.kind === "unavailable") return (
    <main className="guest-shell"><h1>Pesanan belum dapat dilihat</h1><p>Verifikasi sesi tidak tersedia.</p></main>
  );
  const orders = await getOrders();
  return (
    <main className="guest-shell">
      <header className="guest-top"><Link className="guest-wordmark" href="/visit">SMART CAFE</Link>
        <Link href="/visit/cart">Keranjang</Link></header>
      <nav className="guest-breadcrumb"><Link href="/visit">Menu</Link><span>/</span><strong>Pesanan</strong></nav>
      <div className="guest-live-line"><LiveRefresh url={`/visit/live?topic=orders`} /></div>
      <h1>Pesanan saya</h1><p className="guest-subtitle">Pantau progres makanan dan pembayaran secara terpisah.</p>
      <form method="GET"><button type="submit" className="guest-secondary">Segarkan status</button></form>
      {!orders.ok && <section className="guest-panel"><h2>Pesanan tidak dapat dimuat</h2>
        <p>Silakan coba lagi setelah koneksi pulih.</p></section>}
      {orders.ok && orders.data.length === 0 && <section className="guest-panel">
        <h2>Belum ada pesanan</h2><Link href="/visit">Pilih menu</Link></section>}
      {orders.ok && orders.data.map((order) => (
        <section className="guest-panel guest-order-row" key={order.id}>
          <div><p className="guest-eyebrow">PESANAN</p><h2>{order.status}</h2>
            <p>Pembayaran: <strong>{order.paymentStatus}</strong></p>
            <p className="guest-price">{idr(order.totalIdr)}</p></div>
          <Link className="guest-secondary" href={`/visit/orders/${order.id}`}>Detail</Link>
        </section>
      ))}
      <p className="guest-footnote">Hanya pesanan dari sesi tamu dan meja ini yang bisa dilihat.</p>
    </main>
  );
}
