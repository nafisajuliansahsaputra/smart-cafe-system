import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import { addCartItem } from "../../actions";
import { getMenu, getVariants, guestIdentity, idr, UUID_PATTERN } from "@/lib/guest-server";

export const dynamic = "force-dynamic";
const messages: Record<string, string> = {
  invalid: "Pilih semua opsi wajib dan isi jumlah yang benar.",
  conflict: "Keranjang atau menu berubah. Periksa kembali pilihan dan coba lagi.",
  missing: "Item ini tidak tersedia lagi.",
  offline: "Belum ada konfirmasi server. Coba lagi setelah koneksi pulih.",
  expired: "Sesi kunjungan sudah habis. Bergabung kembali lewat QR meja.",
  failed: "Menu belum dapat dipilih.",
};
export default async function MenuItemPage({ params, searchParams }: {
  params: Promise<{ itemId: string }>;
  searchParams: Promise<{ notice?: string }>;
}) {
  const identity = await guestIdentity();
  if (identity.kind === "signed-out") redirect("/visit");
  if (identity.kind === "unavailable") return (
    <main className="guest-shell"><h1>Koneksi belum tersedia</h1><p>Identitas tamu belum dapat diverifikasi.</p></main>
  );
  const { itemId } = await params;
  if (!UUID_PATTERN.test(itemId)) notFound();
  const [menu, variants] = await Promise.all([getMenu(), getVariants(itemId)]);
  const item = menu.ok ? menu.data.find((m) => m.id === itemId) : undefined;
  if (menu.ok && !item) notFound();
  const { notice } = await searchParams;
  const message = notice && Object.hasOwn(messages, notice) ? messages[notice] : null;
  return (
    <main className="guest-shell">
      <header className="guest-top"><Link href="/visit" className="guest-wordmark">SMART CAFE</Link>
        <Link href="/visit/cart">Keranjang</Link></header>
      <nav className="guest-breadcrumb"><Link href="/visit">Menu</Link><span>/</span><strong>Pilihan</strong></nav>
      {message && <p className="guest-alert" role="alert">{message}</p>}
      {!menu.ok || !variants.ok ? (
        <section className="guest-panel"><h1>Menu belum bisa dimuat</h1>
          <p>Periksa koneksi server atau coba lagi. Tidak ada item yang ditambahkan.</p>
          <Link href={`/visit/menu/${itemId}`}>Coba lagi</Link></section>
      ) : item && (
        <section className="guest-panel">
          <p className="guest-eyebrow">{item.category}</p><h1>{item.name}</h1>
          <p className="guest-price">{idr(item.priceIdr)} <span>harga dasar</span></p>
          <p className="guest-subtitle">Tentukan pilihan sebelum masuk keranjang. Opsi tambahan dapat memengaruhi total.</p>
          <form action={addCartItem} className="guest-form">
            <input type="hidden" name="itemId" value={itemId} />
            {variants.data.map((group) => (
              <fieldset key={group.id} className="guest-options">
                <legend>{group.name} {group.required ? <small>(wajib)</small> : <small>(opsional)</small>}</legend>
                {!group.required && (
                  <label><input type="radio" name={`group:${group.id}`} value="" defaultChecked />
                    <span>Tanpa tambahan</span></label>
                )}
                {group.options.map((option, index) => (
                  <label key={option.id}>
                    <input type="radio" name={`group:${group.id}`} value={option.id}
                      required={group.required} defaultChecked={group.required && index === 0} />
                    <span>{option.label}</span>
                    <small>{option.priceDeltaIdr === 0 ? "Termasuk" :
                      `${option.priceDeltaIdr > 0 ? "+" : "-"}${idr(Math.abs(option.priceDeltaIdr))}`}</small>
                  </label>
                ))}
                {group.required && group.options.length === 0 && (
                  <p role="alert">Pilihan wajib belum tersedia. Item tidak dapat dipesan.</p>
                )}
              </fieldset>
            ))}
            <label htmlFor="quantity">Jumlah porsi (1–20)</label>
            <input id="quantity" name="quantity" type="number" min={1}
              max={Math.min(20, item.remainingQuantity ?? 20)} defaultValue={1} required />
            <p className="guest-footnote">Item yang sama di keranjang akan diganti dengan jumlah dan varian baru. Konfigurasi berbeda untuk satu item belum didukung.</p>
            <button className="guest-primary" type="submit"
              disabled={variants.data.some((g) => g.required && g.options.length === 0)}>
              Simpan ke keranjang
            </button>
          </form>
        </section>
      )}
    </main>
  );
}
