import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import { staffIdentity } from "@/lib/staff-server";
import { tableRegistry } from "@/lib/table-registry-server";
import { createTable, editTable } from "./actions";

export const dynamic = "force-dynamic";

const messages: Record<string, string> = {
  created: "Meja berhasil ditambahkan. Link publik dapat dicetak ke QR atau ditulis pada tag NFC.",
  updated: "Kapasitas atau status meja berhasil diperbarui.",
  invalid: "Kode, alias, kapasitas atau konfirmasi tidak valid.",
  conflict: "Meja sudah berubah, kode/alias sudah terdaftar, atau meja masih dipakai. Segarkan daftar.",
  forbidden: "Backend menolak perubahan. Hanya manager atau admin yang diizinkan.",
  missing: "Meja tidak ditemukan. Segarkan daftar.",
  offline: "Penyimpanan belum dapat dikonfirmasi. Periksa daftar sebelum mencoba lagi.",
};
export default async function TableRegistry({ searchParams }: {
  searchParams: Promise<{ notice?: string; q?: string; page?: string }>;
}) {
  const identity = await staffIdentity();
  if (identity.kind === "signed-out") redirect("/staff/login?status=expired");
  if (identity.kind === "unavailable") return <main className="staff-shell">
    <h1>Manajemen meja belum tersedia</h1>
    <p role="alert">Koneksi identitas tidak dapat diverifikasi. Tidak ada operasi yang diizinkan.</p>
    <Link href="/staff/tables">Kembali</Link>
  </main>;
  if (!identity.staff.roles.some(role => role === "MANAGER" || role === "ADMIN")) notFound();
  const query = await searchParams;
  const q = typeof query.q === "string" ? query.q.trim() : "";
  const validPage = typeof query.page === "undefined" ||
    /^(0|[1-9][0-9]{0,3})$/.test(query.page);
  const page = validPage ? Number(query.page ?? "0") : -1;
  const result = await tableRegistry(page, q);
  if (!result.ok && result.status === 401) redirect("/staff/login?status=expired");
  const pageUrl = (n: number) => "/staff/tables/registry?" +
    new URLSearchParams({ page: String(n), ...(q ? { q } : {}) }).toString();
  const notice = query.notice && Object.hasOwn(messages, query.notice) ? messages[query.notice] : null;

  return <main className="staff-shell staff-wide">
    <header className="staff-header"><div>
      <div className="staff-eyebrow">SMART CAFE / MANAGER + ADMIN</div>
      <h1>Inventaris meja</h1>
      <p className="staff-muted">Daftarkan meja fisik, link QR/NFC yang permanen,
        kapasitas tempat duduk dan status operasional. Kunjungan dikelola waiter.</p>
    </div><div className="staff-actions"><Link className="staff-link-button" href="/staff/tables/registry/print">Cetak QR meja</Link><Link className="staff-link-button" href="/staff/tables">Kembali ke meja aktif</Link></div></header>
    <nav className="staff-breadcrumb"><Link href="/staff">Ruang staf</Link><span>/</span>
      <Link href="/staff/tables">Meja</Link><span>/</span><strong>Inventaris</strong></nav>
    {notice && <p className="staff-notice" role={["created", "updated"].includes(query.notice ?? "")
      ? "status" : "alert"}>{notice}</p>}
    {!result.ok ? <section className="staff-panel" role="alert">
      <h2>Inventaris belum dapat dimuat</h2>
      <p>{result.status === 400
        ? "Pencarian tidak valid. Gunakan huruf, angka, garis bawah, atau tanda hubung."
        : "Gagal memverifikasi data dari backend. Penambahan dan perubahan meja dinonaktifkan."}</p>
    </section> : <>
      <section className="staff-panel" aria-labelledby="registry-create-heading">
        <h2 id="registry-create-heading">Tambah meja fisik</h2>
        <p className="staff-muted">Kode meja harus unik. Alias publik menjadi bagian link QR/NFC
          dan tidak bisa diubah sembarangan setelah kode ditempel pada meja.
          Meja baru langsung berstatus AVAILABLE, tetapi tamu tetap membutuhkan
          kode bergabung sementara dari waiter untuk memesan.</p>
        <form action={createTable} className="staff-form">
          <label htmlFor="registry-code">Kode meja (huruf/angka, tanpa spasi)</label>
          <input id="registry-code" name="tableCode" maxLength={40} required
            pattern="[A-Za-z0-9][A-Za-z0-9_-]{0,39}" placeholder="VIP-03"/>
          <label htmlFor="registry-alias">Alias publik URL (huruf kecil, angka, tanda hubung)</label>
          <input id="registry-alias" name="publicAlias" maxLength={100} required
            pattern="[a-z0-9]+(-[a-z0-9]+)*" placeholder="vip-03"/>
          <label htmlFor="registry-capacity">Kapasitas tamu (1 sampai 30)</label>
          <input id="registry-capacity" name="capacity" type="number"
            min="1" max="30" step="1" required defaultValue={4}/>
          <label className="staff-check"><input type="checkbox" name="confirm" value="yes" required/>
            Saya sudah memverifikasi meja fisik dan alias unik untuk QR/NFC</label>
          <button type="submit" className="staff-primary">Daftarkan meja</button>
        </form>
      </section>
      <section className="staff-panel" aria-labelledby="registry-list-heading">
        <h2 id="registry-list-heading">Daftar meja ({result.data.total})</h2>
        <p className="staff-muted">Menonaktifkan meja yang memiliki kunjungan aktif akan ditolak
          oleh backend, sekalipun status halaman belum sempat diperbarui.</p>
        <form method="GET" className="staff-catalog-search">
          <label htmlFor="table-registry-search">Cari kode meja atau alias publik</label>
          <div>
            <input id="table-registry-search" name="q" type="search"
              defaultValue={q.slice(0, 60)} maxLength={60}
              pattern="[A-Za-z0-9_-]*" placeholder="Contoh: VIP-03"/>
            <button type="submit" className="staff-secondary">Cari meja</button>
            {q && <Link href="/staff/tables/registry">Reset</Link>}
          </div>
        </form>
        <p className="staff-muted">Halaman {result.data.page + 1}, maksimum
          {result.data.pageSize} meja per halaman.</p>
        {result.data.items.length === 0 ? <p role="status">Tidak ada meja yang cocok.</p> :
          <div className="staff-task-grid">{result.data.items.map(table => <article
            className="staff-panel staff-task-card" key={table.tableId}>
            <div className="staff-task-head"><h3>Meja {table.tableCode}</h3><strong>
              {table.tableStatus === "AVAILABLE" ? "TERSEDIA" : "NONAKTIF"}
            </strong></div>
            <p>Kapasitas: {table.capacity} · {table.activeVisit ? "Sedang digunakan" : "Tidak ada kunjungan aktif"}</p>
            <p>QR/NFC: <Link href={"/t/" + table.publicAlias}
              target="_blank" rel="noopener noreferrer">{"/t/" + table.publicAlias}</Link></p>
            <p className="staff-muted">Tempel link ke tag NFC atau gunakan sebagai URL isi QR.
              QR hanya identitas meja, bukan token otorisasi pelanggan.</p>
            <form className="staff-form" action={editTable}>
              <input type="hidden" name="tableId" value={table.tableId}/>
              <input type="hidden" name="expectedStatus" value={table.tableStatus}/>
              <input type="hidden" name="expectedCapacity" value={table.capacity}/>
              <label htmlFor={"table-cap-" + table.tableId}>Kapasitas</label>
              <input id={"table-cap-" + table.tableId} name="capacity" type="number"
                min="1" max="30" step="1" defaultValue={table.capacity} required/>
              <label htmlFor={"table-status-" + table.tableId}>Status</label>
              <select id={"table-status-" + table.tableId} name="status"
                defaultValue={table.tableStatus}>
                <option value="AVAILABLE">AVAILABLE</option>
                <option value="OUT_OF_SERVICE" disabled={table.activeVisit}>
                  OUT_OF_SERVICE
                </option>
              </select>
              <label className="staff-check"><input type="checkbox" name="confirm"
                value="yes" required/>Saya telah memeriksa status meja secara fisik</label>
              <button type="submit" className="staff-secondary">Simpan status / kapasitas</button>
            </form>
          </article>)}</div>}
        <nav className="staff-breadcrumb" aria-label="Halaman inventaris">
          {result.data.page > 0 && <Link className="staff-link-button"
            href={pageUrl(result.data.page - 1)}>Halaman sebelumnya</Link>}
          <span>Halaman {result.data.page + 1}</span>
          {result.data.hasNext && result.data.page < 1000 &&
            <Link className="staff-link-button" href={pageUrl(result.data.page + 1)}>
              Halaman berikutnya
            </Link>}
        </nav>
      </section>
    </>}
    <p className="staff-footnote">Link QR/NFC bukan kredensial. Setelah waiter
      membuka kunjungan, tamu tetap harus mendapatkan bukti bergabung
      sementara. Status dan kapasitas tersimpan di PostgreSQL dengan audit
      tindakan. Manager dapat menggunakan Studio QR untuk mencetak atau
      mengunduh SVG, lalu menguji pemindaian di perangkat pelanggan.</p>
  </main>;
}
