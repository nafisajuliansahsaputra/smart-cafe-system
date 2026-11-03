import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import { catalogListing } from "@/lib/catalog-server";
import { idr, staffIdentity } from "@/lib/staff-server";
import { createCategory, createItem, toggleCategory } from "./actions";

export const dynamic = "force-dynamic";

const notices: Record<string, string> = {
  "category-created": "Kategori baru berhasil dibuat.",
  "category-updated": "Status kategori diperbarui.",
  "item-created": "Item dibuat sebagai HIDDEN. Periksa stok dan variannya sebelum diterbitkan.",
  invalid: "Input atau konfirmasi tidak valid. Tidak ada perubahan disimpan.",
  forbidden: "Server menolak perubahan ini berdasarkan peran staf.",
  missing: "Data sudah tidak ditemukan. Segarkan daftar.",
  conflict: "Perubahan bertabrakan dengan pembaruan lain. Segarkan data sebelum mencoba lagi.",
  offline: "Status penyimpanan belum dapat dikonfirmasi. Periksa data sebelum mengulang.",
};

export default async function CatalogPage({ searchParams }: {
  searchParams: Promise<{ notice?: string; q?: string; page?: string }>;
}) {
  const identity = await staffIdentity();
  if (identity.kind === "signed-out") redirect("/staff/login?status=expired");
  if (identity.kind === "unavailable") return <main className="staff-shell">
    <h1>Identitas pengelola belum dapat diverifikasi</h1>
    <p role="alert">Manajemen menu tidak aktif sampai koneksi autentikasi pulih.</p>
    <Link href="/staff">Kembali</Link>
  </main>;
  if (!identity.staff.roles.some(r => r === "ADMIN" || r === "MANAGER")) notFound();
  const query = await searchParams;
  const search = typeof query.q === "string" ? query.q.trim() : "";
  const validPage = typeof query.page === "undefined" ||
    /^(0|[1-9][0-9]{0,3})$/.test(query.page);
  const requested = validPage ? Number(query.page ?? "0") : -1;
  const result = await catalogListing(requested, search);
  if (!result.ok && result.status === 401) redirect("/staff/login?status=expired");
  const notice = typeof query.notice === "string" && Object.hasOwn(notices, query.notice)
    ? notices[query.notice] : null;
  const categories = result.ok ? result.data.categories : [];
  const listing = result.ok ? result.data.listing : null;
  const visible = listing?.items ?? [];
  const page = listing?.page ?? 0;
  const total = listing?.total ?? 0;
  const hasNext = listing?.hasNext ?? false;
  const searchQuery = (n: number) => "/staff/catalog?" + new URLSearchParams({
    ...(search ? { q: search } : {}), page: String(n),
  }).toString();

  return <main className="staff-shell staff-wide">
    <header className="staff-header"><div>
      <div className="staff-eyebrow">SMART CAFE / MANAGER + ADMIN</div>
      <h1>Menu &amp; stok</h1>
      <p className="staff-muted">Kelola kategori, produk, harga, publikasi dan jumlah stok
        dari database nyata. Perubahan tercatat di audit server.</p>
    </div><Link className="staff-link-button" href="/staff">Ruang operasional</Link></header>
    <nav className="staff-breadcrumb" aria-label="Lokasi halaman">
      <Link href="/staff">Ruang staf</Link><span>/</span><strong>Menu &amp; stok</strong>
    </nav>
    {notice && <p className="staff-notice" role={query.notice?.endsWith("created") ||
        query.notice?.endsWith("updated") ? "status" : "alert"}>{notice}</p>}
    {!result.ok && <section className="staff-panel" role="alert">
      <h2>Data katalog tidak tersedia</h2>
      <p>{result.status === 400
        ? "Format pencarian atau halaman tidak valid. Gunakan maksimal 80 karakter."
        : "Server belum mengonfirmasi daftar kategori dan produk. Tindakan dinonaktifkan, bukan menganggap katalog kosong."}</p>
    </section>}

    {result.ok && <>
      <section className="staff-panel" aria-labelledby="catalog-category-title">
        <h2 id="catalog-category-title">Kategori menu ({categories.length})</h2>
        <p className="staff-muted">Kategori nonaktif menyembunyikan produk dari daftar pelanggan,
          tanpa menghapus histori pesanan.</p>
        {categories.length === 0 ? <p>Belum ada kategori.</p> :
          <div className="staff-catalog-categories">{categories.map(category =>
            <article key={category.id} className="staff-catalog-category">
              <div><strong>{category.name}</strong><small>/{category.slug} · {category.active ? "AKTIF" : "NONAKTIF"}</small></div>
              <form className="staff-catalog-toggle" action={toggleCategory}>
                <input type="hidden" name="categoryId" value={category.id}/>
                <input type="hidden" name="active" value={String(!category.active)}/>
                <label className="staff-check"><input type="checkbox" name="confirm"
                  value="yes" required/>Konfirmasi</label>
                <button type="submit" className="staff-secondary">
                  {category.active ? "Nonaktifkan" : "Aktifkan"}
                </button>
              </form>
            </article>)}</div>}
        <details className="staff-catalog-details">
          <summary>Tambah kategori baru</summary>
          <form action={createCategory} className="staff-form">
            <label htmlFor="catalog-category-name">Nama kategori</label>
            <input id="catalog-category-name" name="name" maxLength={120} required
              placeholder="Contoh: Signature Coffee"/>
            <label htmlFor="catalog-category-slug">Slug unik (huruf kecil, angka, tanda hubung)</label>
            <input id="catalog-category-slug" name="slug" maxLength={120}
              pattern="[a-z0-9]+(-[a-z0-9]+)*" required placeholder="signature-coffee"/>
            <label className="staff-check"><input type="checkbox" name="confirm" value="yes"
              required/>Saya sudah memeriksa nama dan slug kategori</label>
            <button type="submit" className="staff-primary">Buat kategori</button>
          </form>
        </details>
      </section>
      <section className="staff-panel" aria-labelledby="catalog-new-item-title">
        <h2 id="catalog-new-item-title">Tambah produk</h2>
        <p className="staff-muted">Produk baru dibuat sebagai <strong>HIDDEN</strong>.
          Atur stok, varian, lalu terbitkan setelah semua informasi benar.</p>
        {categories.length === 0 ? <p>Buat kategori terlebih dahulu.</p> :
          <details className="staff-catalog-details">
            <summary>Buat item menu</summary>
            <form action={createItem} className="staff-form">
              <label htmlFor="catalog-new-name">Nama produk</label>
              <input id="catalog-new-name" name="name" maxLength={120} required
                placeholder="Contoh: Iced Latte"/>
              <label htmlFor="catalog-new-description">Deskripsi singkat (opsional)</label>
              <textarea id="catalog-new-description" name="description" maxLength={500}
                rows={3} placeholder="Contoh: Espresso, susu segar, disajikan dingin"/>
              <label htmlFor="catalog-new-category">Kategori</label>
              <select id="catalog-new-category" name="categoryId" required
                defaultValue={categories.find(item => item.active)?.id ?? categories[0]?.id}>
                {categories.map(category => <option key={category.id} value={category.id}>
                  {category.name} {category.active ? "" : "(nonaktif)"}
                </option>)}
              </select>
              <label htmlFor="catalog-new-price">Harga dasar (IDR)</label>
              <input id="catalog-new-price" type="number" name="priceIdr"
                min="0" max="1000000000" step="1" required placeholder="32000"/>
              <label className="staff-check"><input type="checkbox" name="confirm" value="yes"
                required/>Saya memahami item belum ditampilkan ke pelanggan</label>
              <button type="submit" className="staff-primary">Tambah produk sebagai HIDDEN</button>
            </form>
          </details>}
      </section>
      <section className="staff-panel" aria-labelledby="catalog-items-title">
        <h2 id="catalog-items-title">Daftar produk ({total})</h2>
        <p className="staff-muted">Pencarian dan pagination dilakukan langsung di
          PostgreSQL. Setiap halaman menampilkan paling banyak 20 produk,
          termasuk item yang berada di luar 500 produk pertama.</p>
        <form method="GET" className="staff-catalog-search">
          <label htmlFor="catalog-search">Cari produk, kategori atau status</label>
          <div><input id="catalog-search" type="search" name="q" defaultValue={search.slice(0, 80)}
            placeholder="Cari nama produk" maxLength={80}/>
            <button className="staff-secondary" type="submit">Cari</button>
            {search && <Link href="/staff/catalog">Reset</Link>}
          </div>
        </form>
        {visible.length === 0 ? <p role="status">Tidak ada produk yang cocok.</p> :
          <div className="staff-table-wrap"><table className="staff-table">
            <thead><tr><th>Produk</th><th>Kategori</th><th>Harga</th>
              <th>Status</th><th>Kelola</th></tr></thead>
            <tbody>{visible.map(item => <tr key={item.id}>
              <td><strong>{item.name}</strong></td>
              <td>{categories.find(c => c.id === item.categoryId)?.name ?? "Kategori tidak dikenal"}</td>
              <td>{idr(item.priceIdr)}</td>
              <td><span className="staff-status">{item.availability}</span></td>
              <td><Link className="staff-link-button"
                href={"/staff/catalog/" + item.id}>Edit / stok / varian</Link></td>
            </tr>)}</tbody>
          </table></div>}
        <nav className="staff-breadcrumb" aria-label="Halaman katalog">
          {page > 0 && <Link href={searchQuery(page - 1)}>Sebelumnya</Link>}
          <span>Halaman {page + 1} dari {Math.max(1, Math.ceil(total / 20))}</span>
          {hasNext && page < 1000 &&
            <Link href={searchQuery(page + 1)}>Selanjutnya</Link>}
        </nav>
      </section>
    </>}
    <p className="staff-footnote">Tindakan dibatasi pada MANAGER/ADMIN, diverifikasi
      ulang oleh backend dan menggunakan versi data untuk menghindari
      penimpaan harga/stok yang berubah. Jangan ulangi operasi ketika status
      API tidak dapat dikonfirmasi.</p>
  </main>;
}
