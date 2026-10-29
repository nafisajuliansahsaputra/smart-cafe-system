import Link from "next/link";
import { exitVisit } from "./actions";
import { getMenu, guestIdentity, idr } from "@/lib/guest-server";

export const dynamic = "force-dynamic";
const messages: Record<string, string> = {
  updated: "Keranjang sudah diperbarui berdasarkan validasi server.",
  invalid: "Pilihan menu atau jumlah item tidak valid.",
  expired: "Sesi telah berakhir. Panggil waiter untuk memperoleh kode bergabung baru.",
  conflict: "Data berubah. Muat ulang halaman lalu coba lagi.",
  offline: "Koneksi ke server gagal. Belum ada perubahan yang dikonfirmasi.",
  failed: "Permintaan belum berhasil.",
};

export default async function VisitPage({ searchParams }: {
  searchParams: Promise<{ notice?: string }>;
}) {
  const identity = await guestIdentity();
  if (identity.kind === "signed-out") return (
    <main className="guest-shell"><p className="guest-eyebrow">SMART CAFE / GUEST</p>
      <h1>Belum bergabung ke meja</h1>
      <p>Scan QR/NFC di meja dan masukkan kode sesi yang diberikan waiter.</p>
      <Link href="/">Kembali ke beranda</Link></main>
  );
  if (identity.kind === "unavailable") return (
    <main className="guest-shell"><h1>Layanan pesanan sedang tidak tersedia</h1>
      <p>Identitas tamu belum dapat diverifikasi. Tidak ada pemesanan yang bisa dilakukan saat ini.</p>
      <Link href="/visit">Coba lagi</Link></main>
  );
  const { notice } = await searchParams;
  const message = notice && Object.hasOwn(messages, notice) ? messages[notice] : null;
  const menu = await getMenu();
  const categories = menu.ok ? [...new Set(menu.data.map((item) => item.category))] : [];
  return (
    <main className="guest-shell guest-wide">
      <header className="guest-top">
        <Link className="guest-wordmark" href="/visit">SMART CAFE</Link>
        <div className="guest-nav">
          <Link href="/visit/cart">Keranjang</Link><Link href="/visit/orders">Pesanan saya</Link>
          <form action={exitVisit}><button className="guest-link-button" type="submit">Keluar</button></form>
        </div>
      </header>
      <div className="guest-hero">
        <p className="guest-eyebrow">MEJA {identity.visit.tableCode} / {identity.visit.displayName}</p>
        <h1>Pilih sesuatu yang enak.</h1>
        <p className="guest-subtitle">Jelajahi menu dan pilih opsi sesuai selera. Harga akhir selalu dihitung ulang oleh server.</p>
      </div>
      {message && <p className="guest-alert" role="status">{message}</p>}
      {!menu.ok && <section className="guest-panel"><h2>Menu belum dapat dimuat</h2>
        <p>Periksa koneksi ke sistem dan coba lagi. Pesanan tidak akan diproses tanpa konfirmasi server.</p>
        <Link href="/visit">Segarkan</Link></section>}
      {menu.ok && menu.data.length === 0 && <section className="guest-panel"><h2>Menu sedang kosong</h2>
        <p>Belum ada makanan atau minuman yang tersedia untuk dipesan.</p></section>}
      {menu.ok && categories.map((category) => (
        <section key={category} className="guest-category">
          <h2>{category}</h2>
          <div className="guest-menu-grid">{menu.data.filter((i) => i.category === category).map((item) => (
            <article className="guest-panel guest-menu-card" key={item.id}>
              <div><h3>{item.name}</h3>
                <p className="guest-price">{idr(item.priceIdr)} <span>harga mulai</span></p>
                {item.remainingQuantity !== null && <small>Sisa {item.remainingQuantity} porsi</small>}
              </div>
              <Link className="guest-secondary" href={`/visit/menu/${item.id}`}>Pilih menu</Link>
            </article>
          ))}</div>
        </section>
      ))}
      <p className="guest-footnote">Menu ditampilkan tanpa simulasi pembayaran. Stok dan harga dicek lagi saat checkout.</p>
    </main>
  );
}
