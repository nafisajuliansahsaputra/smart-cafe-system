import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import { catalogItemDetail, catalogCategories } from "@/lib/catalog-server";
import { MenuPhoto } from "@/components/menu-photo";
import { idr, staffIdentity } from "@/lib/staff-server";
import {
  createGroup, createOption, editGroup, editItem, editOption, updateStock,
} from "../actions";

export const dynamic = "force-dynamic";

const notices: Record<string, string> = {
  "item-updated": "Nama, harga, kategori atau publikasi item berhasil diperbarui.",
  "stock-updated": "Stok berhasil diperbarui dan dicatat pada audit.",
  "group-created": "Grup varian dibuat. Periksa dan tambahkan pilihan sebelum item diterbitkan.",
  "group-updated": "Grup varian berhasil diperbarui.",
  "option-created": "Pilihan varian berhasil ditambahkan.",
  "option-updated": "Pilihan varian berhasil diperbarui.",
  invalid: "Data atau konfirmasi tidak valid. Perubahan dibatalkan.",
  forbidden: "Backend menolak perubahan berdasarkan izin.",
  conflict: "Data berubah sejak halaman dibuka. Muat ulang halaman dan tinjau versi terbaru.",
  missing: "Item, grup atau pilihan sudah tidak ditemukan.",
  offline: "Server belum mengonfirmasi penyimpanan. Periksa versi terbaru sebelum mencoba ulang.",
};
export default async function CatalogItemPage({ params, searchParams }: {
  params: Promise<{ itemId: string }>;
  searchParams: Promise<{ notice?: string }>;
}) {
  const identity = await staffIdentity();
  if (identity.kind === "signed-out") redirect("/staff/login?status=expired");
  if (identity.kind === "unavailable") return <main className="staff-shell">
    <h1>Identitas pengelola belum dapat diverifikasi</h1>
    <p role="alert">Perubahan menu tidak diizinkan saat autentikasi tidak tersedia.</p>
    <Link href="/staff/catalog">Kembali</Link>
  </main>;
  if (!identity.staff.roles.some(r => r === "MANAGER" || r === "ADMIN")) notFound();
  const { itemId } = await params;
  const [detail, catalog, query] = await Promise.all([
    catalogItemDetail(itemId), catalogCategories(), searchParams,
  ]);
  if (!detail.ok && detail.status === 401 || !catalog.ok && catalog.status === 401) {
    redirect("/staff/login?status=expired");
  }
  if (!detail.ok && detail.status === 404) notFound();
  const notice = query.notice && Object.hasOwn(notices, query.notice) ? notices[query.notice] : null;
  if (!detail.ok || !catalog.ok) return <main className="staff-shell">
    <h1>Detail menu tidak dapat dimuat</h1>
    <p role="alert">Tidak ada perubahan yang diizinkan saat stok, varian,
      kategori atau revisi item tidak dapat dikonfirmasi.</p>
    <Link href="/staff/catalog">Kembali ke katalog</Link>
  </main>;
  const { item, stock, groups } = detail.data;
  const categories = catalog.data;
  return <main className="staff-shell staff-wide">
    <header className="staff-header"><div>
      <div className="staff-eyebrow">SMART CAFE / CATALOG EDITOR</div>
      <h1>{item.name}</h1>
      <p className="staff-muted">Status: <strong>{item.availability}</strong> ·
        Harga saat ini: <strong>{idr(item.priceIdr)}</strong> · Revisi {item.revision}</p>
    </div><Link href="/staff/catalog" className="staff-link-button">Kembali ke katalog</Link></header>
    <nav className="staff-breadcrumb"><Link href="/staff">Ruang staf</Link><span>/</span>
      <Link href="/staff/catalog">Katalog</Link><span>/</span><strong>{item.name}</strong></nav>
    {notice && <p className="staff-notice" role={query.notice?.endsWith("updated") ||
        query.notice?.endsWith("created") ? "status" : "alert"}>{notice}</p>}

    <div className="staff-catalog-editor">
      <section className="staff-panel" aria-labelledby="catalog-editor-heading">
        <h2 id="catalog-editor-heading">Identitas, harga &amp; publikasi</h2>
        <p className="staff-muted">Item HIDDEN tidak muncul pada menu pelanggan.
          Ubah hanya setelah mengecek kategori, varian dan ketersediaan stok.</p>
        <form className="staff-form" action={editItem}>
          <input type="hidden" name="itemId" value={item.id}/>
          <input type="hidden" name="expectedRevision" value={item.revision}/>
          <label htmlFor="catalog-edit-name">Nama item</label>
          <input id="catalog-edit-name" name="name" defaultValue={item.name}
            maxLength={120} required/>
          <label htmlFor="catalog-edit-description">Deskripsi menu</label>
          <textarea id="catalog-edit-description" name="description" maxLength={500}
            rows={3} defaultValue={item.description ?? ""}
            placeholder="Bahan utama atau rasa yang membantu pelanggan memilih"/>
          {item.imageUrl && <MenuPhoto src={item.imageUrl} name={item.name}
            className="staff-menu-edit-photo" />}
          <label htmlFor="catalog-edit-image">URL foto produk (Cloudinary)</label>
          <input id="catalog-edit-image" name="imageUrl" type="url" maxLength={700}
            defaultValue={item.imageUrl ?? ""}
            placeholder="https://res.cloudinary.com/.../image/upload/...jpg" />
          <small className="staff-muted">Tautan HTTPS akun Cloudinary yang disetujui.
            Kosongkan untuk menghapus foto. Perubahan dicatat bersama revisi item.</small>
          <label htmlFor="catalog-edit-category">Kategori</label>
          <select id="catalog-edit-category" name="categoryId" required defaultValue={item.categoryId}>
            {categories.map(category => <option key={category.id} value={category.id}>
              {category.name}{category.active ? "" : " (nonaktif)"}
            </option>)}
          </select>
          <label htmlFor="catalog-edit-price">Harga dasar (IDR)</label>
          <input id="catalog-edit-price" name="priceIdr" type="number"
            min="0" max="1000000000" step="1" defaultValue={item.priceIdr} required/>
          <label htmlFor="catalog-edit-visibility">Status penjualan</label>
          <select id="catalog-edit-visibility" name="availability" defaultValue={item.availability}>
            <option value="HIDDEN">HIDDEN · belum dipublikasikan</option>
            <option value="AVAILABLE">AVAILABLE · tampil di menu apabila stok tersedia</option>
            <option value="SOLD_OUT">SOLD_OUT · tidak tersedia untuk dipesan</option>
          </select>
          <label className="staff-check"><input type="checkbox" name="confirm" value="yes" required/>
            Saya telah memeriksa perubahan harga dan status publikasi</label>
          <button type="submit" className="staff-primary">Simpan perubahan menu</button>
        </form>
      </section>

      <section className="staff-panel" aria-labelledby="catalog-stock-heading">
        <h2 id="catalog-stock-heading">Stok &amp; ketersediaan</h2>
        <p className="staff-muted">Version {stock.version} · {stock.available ? "Penjualan diaktifkan" : "Penjualan dinonaktifkan"} ·
          {stock.limitedQuantity === null ? " Tanpa batas kuantitas" :
            " Sisa " + stock.limitedQuantity + " unit"}.</p>
        <form className="staff-form" action={updateStock}>
          <input type="hidden" name="itemId" value={item.id}/>
          <input type="hidden" name="expectedVersion" value={stock.version}/>
          <label className="staff-check"><input type="checkbox" name="available"
            defaultChecked={stock.available}/>Produk dapat dijual (jika status AVAILABLE)</label>
          <label htmlFor="catalog-stock-mode">Kebijakan kuantitas</label>
          <select name="quantityMode" id="catalog-stock-mode"
            defaultValue={stock.limitedQuantity === null ? "unlimited" : "limited"}>
            <option value="unlimited">Tidak dibatasi jumlah item</option>
            <option value="limited">Jumlah stok terbatas</option>
          </select>
          <label htmlFor="catalog-stock-amount">Jumlah tersisa (wajib jika terbatas)</label>
          <input id="catalog-stock-amount" type="number" name="limitedQuantity"
            min="0" max="1000000000" step="1"
            defaultValue={stock.limitedQuantity ?? 0}/>
          <label htmlFor="catalog-stock-reason">Alasan penyesuaian stok</label>
          <input id="catalog-stock-reason" name="reason" minLength={4} maxLength={300}
            required placeholder="Contoh: restock fisik 20 unit"/>
          <label className="staff-check"><input type="checkbox" name="confirm" value="yes" required/>
            Saya telah memeriksa stok secara fisik</label>
          <button className="staff-primary" type="submit">Simpan stok dengan audit</button>
        </form>
      </section>
    </div>

    <section className="staff-panel" aria-labelledby="catalog-variants-heading">
      <h2 id="catalog-variants-heading">Grup varian ({groups.length}/8)</h2>
      <p className="staff-muted">Setiap grup aktif menerima maksimal satu pilihan.
        Grup wajib harus memiliki pilihan aktif sebelum pelanggan bisa checkout.
        Seluruh perubahan dilindungi nomor revisi.</p>
      {groups.length === 0 && <p role="status">Belum ada grup varian. Menu dapat dipesan
        tanpa modifier jika tidak memerlukannya.</p>}
      <div className="staff-catalog-variants">{groups.map(group => <article
        key={group.id} className="staff-catalog-variant">
        <h3>{group.name} <small>· {group.active ? "AKTIF" : "NONAKTIF"} · {group.required ? "WAJIB" : "OPSIONAL"}</small></h3>
        <details className="staff-catalog-details">
          <summary>Ubah grup (revisi {group.revision})</summary>
          <form action={editGroup} className="staff-form">
            <input type="hidden" name="itemId" value={item.id}/>
            <input type="hidden" name="groupId" value={group.id}/>
            <input type="hidden" name="expectedRevision" value={group.revision}/>
            <label htmlFor={"group-name-" + group.id}>Nama grup</label>
            <input id={"group-name-" + group.id} name="name" maxLength={80}
              defaultValue={group.name} required/>
            <label className="staff-check"><input type="checkbox" name="required"
              defaultChecked={group.required}/>Pelanggan wajib memilih dari grup ini</label>
            <label className="staff-check"><input type="checkbox" name="active"
              defaultChecked={group.active}/>Grup aktif</label>
            <label className="staff-check"><input type="checkbox" name="confirm" value="yes"
              required/>Konfirmasi perubahan grup varian</label>
            <button className="staff-secondary" type="submit">Simpan grup</button>
          </form>
        </details>
        <h4>Pilihan ({group.options.length})</h4>
        {group.options.length === 0 ? <p>Belum ada pilihan di grup ini.</p> :
          <div className="staff-catalog-options">{group.options.map(option =>
            <details className="staff-catalog-details" key={option.id}>
              <summary>{option.label} · {option.priceDeltaIdr < 0 ? "-" : "+"}
                {idr(Math.abs(option.priceDeltaIdr))} · {option.active ? "AKTIF" : "NONAKTIF"}</summary>
              <form className="staff-form" action={editOption}>
                <input type="hidden" name="itemId" value={item.id}/>
                <input type="hidden" name="optionId" value={option.id}/>
                <input type="hidden" name="expectedRevision" value={option.revision}/>
                <label htmlFor={"option-label-" + option.id}>Nama pilihan ({option.key})</label>
                <input id={"option-label-" + option.id} name="label"
                  defaultValue={option.label} maxLength={80} required/>
                <label htmlFor={"option-delta-" + option.id}>Penyesuaian harga (IDR, bisa negatif)</label>
                <input id={"option-delta-" + option.id} name="priceDeltaIdr" type="number"
                  min="-1000000000" max="1000000000" step="1" defaultValue={option.priceDeltaIdr} required/>
                <label className="staff-check"><input type="checkbox" name="active"
                  defaultChecked={option.active}/>Pilihan aktif</label>
                <label className="staff-check"><input type="checkbox" name="confirm" value="yes"
                  required/>Konfirmasi perubahan pilihan</label>
                <button type="submit" className="staff-secondary">Simpan pilihan</button>
              </form>
            </details>)}</div>}
        <details className="staff-catalog-details">
          <summary>Tambah pilihan varian</summary>
          <form action={createOption} className="staff-form">
            <input type="hidden" name="itemId" value={item.id}/>
            <input type="hidden" name="groupId" value={group.id}/>
            <label htmlFor={"new-key-" + group.id}>Kode unik pilihan</label>
            <input id={"new-key-" + group.id} name="key" required maxLength={60}
              pattern="[a-z0-9]+(-[a-z0-9]+)*" placeholder="oat-milk"/>
            <label htmlFor={"new-label-" + group.id}>Label pelanggan</label>
            <input id={"new-label-" + group.id} name="label" maxLength={80}
              placeholder="Oat Milk" required/>
            <label htmlFor={"new-delta-" + group.id}>Tambah/kurangi harga (IDR)</label>
            <input id={"new-delta-" + group.id} name="priceDeltaIdr" type="number"
              min="-1000000000" max="1000000000" step="1" defaultValue={0} required/>
            <label className="staff-check"><input type="checkbox" name="confirm" value="yes"
              required/>Saya memahami pilihan baru dapat memengaruhi harga checkout</label>
            <button type="submit" className="staff-primary">Tambah pilihan</button>
          </form>
        </details>
      </article>)}</div>
      {groups.length < 8 && <details className="staff-catalog-details">
        <summary>Tambah grup varian baru</summary>
        <form action={createGroup} className="staff-form">
          <input type="hidden" name="itemId" value={item.id}/>
          <label htmlFor="new-group-name">Nama grup</label>
          <input id="new-group-name" name="name" maxLength={80} required
            placeholder="Contoh: Pilihan susu"/>
          <label className="staff-check"><input type="checkbox" name="required"/>
            Pelanggan wajib memilih satu pilihan</label>
          <label className="staff-check"><input type="checkbox" name="confirm" value="yes"
            required/>Saya memahami grup harus diberi pilihan sebelum dipublikasikan</label>
          <button type="submit" className="staff-primary">Tambah grup</button>
        </form>
      </details>}
    </section>
    <p className="staff-footnote">Layar ini bukan editor transaksi. Harga yang sudah
      tersimpan di pesanan tidak berubah ketika katalog diperbarui. Backend menghitung
      ulang checkout dan mencatat audit. Jika terdeteksi konflik revisi, segarkan
      halaman dan jangan menimpa perubahan staf lain.</p>
  </main>;
}
