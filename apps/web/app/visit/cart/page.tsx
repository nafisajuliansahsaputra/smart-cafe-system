import { randomUUID } from "node:crypto";
import Link from "next/link";
import { redirect } from "next/navigation";
import { checkoutCash, checkoutSandbox, updateCartItem } from "../actions";
import { getCart, guestIdentity, idr, sandboxCheckoutEnabled } from "@/lib/guest-server";

export const dynamic = "force-dynamic";
const messages: Record<string, string> = {
  updated: "Keranjang diperbarui. Perkiraan total dihitung server.",
  conflict: "Keranjang, stok, atau harga telah berubah. Muat ulang sebelum melanjutkan.",
  invalid: "Jumlah item atau konfirmasi belum benar.",
  missing: "Item tidak tersedia lagi.",
  offline: "Server tidak memberi konfirmasi. Periksa pesanan sebelum checkout ulang.",
  expired: "Sesi telah berakhir. Bergabung kembali melalui QR meja.",
  failed: "Perubahan gagal diproses.",
};

export default async function CartPage({ searchParams }: {
  searchParams: Promise<{ notice?: string }>;
}) {
  const identity = await guestIdentity();
  if (identity.kind === "signed-out") redirect("/visit");
  if (identity.kind === "unavailable") return (
    <main className="guest-shell"><h1>Keranjang tidak tersedia</h1>
      <p>Identitas belum dapat diverifikasi. Tidak ada transaksi yang dilakukan.</p></main>
  );
  const sandbox = sandboxCheckoutEnabled();
  const { notice } = await searchParams;
  const message = notice && Object.hasOwn(messages, notice) ? messages[notice] : null;
  const cart = await getCart();
  if (!cart.ok && (cart.status === 401 || cart.status === 403)) redirect("/visit");
  const valid = cart.ok && cart.data.lines.length > 0 &&
    cart.data.lines.every((line) => line.available) && cart.data.estimatedSubtotalIdr > 0;
  return (
    <main className="guest-shell">
      <header className="guest-top"><Link className="guest-wordmark" href="/visit">SMART CAFE</Link>
        <Link href="/visit/orders">Pesanan saya</Link></header>
      <nav className="guest-breadcrumb"><Link href="/visit">Menu</Link><span>/</span><strong>Keranjang</strong></nav>
      <h1>Keranjangmu</h1>
      <p className="guest-subtitle">Ubah jumlah, cek rincian dan konfirmasi pesanan.</p>
      {message && <p className="guest-alert" role="status">{message}</p>}
      {!cart.ok && <section className="guest-panel"><h2>Keranjang gagal dimuat</h2>
        <p>Coba segarkan saat server telah pulih.</p><Link href="/visit/cart">Coba lagi</Link></section>}
      {cart.ok && cart.data.lines.length === 0 && <section className="guest-panel">
        <h2>Keranjang masih kosong</h2><p>Pilih menu dulu, baru checkout.</p>
        <Link className="guest-primary" href="/visit">Jelajahi menu</Link></section>}
      {cart.ok && cart.data.lines.length > 0 && (
        <>
          {cart.data.lines.map((line) => (
            <section className="guest-panel guest-cart-line" key={line.menuItemId}>
              <div><h2>{line.name}</h2>
                {line.variantLabels.length > 0 && <p className="guest-subtitle">{line.variantLabels.join(" · ")}</p>}
                <p className="guest-price">{idr(line.lineTotalIdr)} <span>({idr(line.unitPriceIdr)} / unit)</span></p>
                {!line.available && <p className="guest-alert">Stok atau opsi menu berubah. Perbarui item sebelum checkout.</p>}
              </div>
              <form action={updateCartItem} className="guest-quantity-form">
                <input type="hidden" name="itemId" value={line.menuItemId} />
                <input type="hidden" name="expectedVersion" value={cart.data.version} />
                <label htmlFor={`quantity-${line.menuItemId}`}>Jumlah</label>
                <input id={`quantity-${line.menuItemId}`} name="quantity" type="number"
                  min={0} max={20} defaultValue={line.quantity} required />
                <button type="submit" className="guest-secondary">Perbarui (0 = hapus)</button>
              </form>
              <Link href={`/visit/menu/${line.menuItemId}`}>Ganti pilihan</Link>
            </section>
          ))}
          <section className="guest-panel">
            <div className="guest-cart-total"><span>Perkiraan subtotal</span>
              <strong>{idr(cart.data.estimatedSubtotalIdr)}</strong></div>
            <p className="guest-footnote">Belum mengikat stok atau harga. Harga total final diverifikasi saat checkout. Pembayaran QRIS/VA sungguhan belum tersedia.</p>
            <form action={checkoutCash} className="guest-form">
              <input type="hidden" name="cartVersion" value={cart.data.version} />
              <input type="hidden" name="idempotencyKey" value={randomUUID()} />
              <label className="guest-check"><input type="checkbox" name="confirm" value="yes" required />
                <span>Saya setuju membuat pesanan cash. Pembayaran dilakukan setelah pesanan diantar, sesuai instruksi staf.</span></label>
              <button className="guest-primary" type="submit" disabled={!valid}>Buat pesanan cash</button>
            </form>
          </section>
          {sandbox && <section className="guest-panel" aria-labelledby="sandbox-payment-title">
            <h2 id="sandbox-payment-title">Simulasi pembayaran digital (SANDBOX)</h2>
            <p className="guest-alert">
              KHUSUS PENGUJIAN. QRIS/VA di sini palsu, tidak bisa dibayar,
              tidak menarik uang, dan tidak membuat pesanan lunas.
            </p>
            {(["QRIS","VA"] as const).map((method)=>(
              <form key={method} action={checkoutSandbox} className="guest-form">
                <input type="hidden" name="cartVersion" value={cart.data.version}/>
                <input type="hidden" name="paymentMethod" value={method}/>
                <input type="hidden" name="idempotencyKey" value={randomUUID()}/>
                <label className="guest-check">
                  <input type="checkbox" name="confirm" value="yes" required/>
                  <span>Saya memahami pesanan {method} ini hanya simulasi,
                    berstatus menunggu pembayaran, dan tidak diproses sebagai pembayaran nyata.</span>
                </label>
                <button type="submit" className="guest-secondary" disabled={!valid}>
                  Simulasikan pesanan {method}
                </button>
              </form>
            ))}
          </section>}
        </>
      )}
    </main>
  );
}
