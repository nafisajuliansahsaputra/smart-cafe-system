import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import { adminTables } from "@/lib/table-admin-server";
import { staffIdentity } from "@/lib/staff-server";
import { createCafeTable, updateCafeTable } from "./actions";

export const dynamic = "force-dynamic";

const messages: Record<string, string> = {
  created: "Meja baru sudah dibuat di database.",
  updated: "Kapasitas atau status meja berhasil diperbarui.",
  invalid: "Masukan atau konfirmasi tidak valid.",
  conflict: "Kode/alias sudah terpakai atau kunjungan meja masih aktif. Tidak ada perubahan.",
  missing: "Meja tidak ditemukan. Segarkan halaman.",
  forbidden: "Tindakan tidak diizinkan untuk peran ini.",
  offline: "Server belum mengonfirmasi perubahan. Segarkan sebelum mencoba kembali.",
  unconfirmed: "Respons tidak dapat dikonfirmasi. Periksa status sebelum mengulang.",
};

export default async function TableSettingsPage({
  searchParams,
}: { searchParams: Promise<{ notice?: string }> }) {
  const identity = await staffIdentity();
  if (identity.kind === "signed-out") redirect("/staff/login?status=expired");
  if (identity.kind === "unavailable") return <main className="staff-shell">
    <h1>Manajemen meja belum tersedia</h1>
    <p role="alert">Identitas operator tidak dapat dikonfirmasi. Perubahan dinonaktifkan.</p>
    <Link href="/staff">Kembali</Link>
  </main>;
  if (!identity.staff.roles.some(r => r === "MANAGER" || r === "ADMIN")) notFound();
  const result = await adminTables();
  if (!result.ok && result.status === 401) redirect("/staff/login?status=expired");
  const { notice } = await searchParams;
  const message = typeof notice === "string" && Object.hasOwn(messages, notice)
    ? messages[notice] : null;
  return <main className="staff-shell staff-wide">
    <header className="staff-header"><div>
      <div className="staff-eyebrow">SMART CAFE / MANAGER + ADMIN</div>
      <h1>Pengaturan meja</h1>
      <p className="staff-muted">Daftarkan meja fisik, atur kapasitas dan nonaktifkan
        meja yang tidak dapat digunakan. Perubahan tersimpan dan diaudit di server.</p>
    </div><Link href="/staff">Ruang operasional</Link></header>
    <nav className="staff-breadcrumb" aria-label="Lokasi halaman">
      <Link href="/staff">Ruang staf</Link><span>/</span><strong>Pengaturan meja</strong>
    </nav>
    {message && <p className="staff-notice" role={["created", "updated"].includes(notice ?? "")
      ? "status" : "alert"}>{message}</p>}
    {!result.ok ? <section className="staff-panel" role="alert">
      <h2>Data meja belum dapat diverifikasi</h2>
      <p>Tidak ada tombol perubahan aktif hingga server mengonfirmasi daftar meja.</p>
    </section> : <>
      <section className="staff-panel">
        <h2>Tambah meja fisik</h2>
        <p className="staff-muted">Kode dan alias QR/NFC permanen. Jangan gunakan
          kredensial sesi atau informasi pelanggan sebagai alias.</p>
        <form action={createCafeTable} className="staff-form">
          <label htmlFor="table-new-code">Kode meja (huruf kapital, angka, tanda hubung)</label>
          <input id="table-new-code" name="tableCode" pattern="[A-Z0-9][A-Z0-9-]*"
            maxLength={40} required placeholder="A-12"/>
          <label htmlFor="table-new-alias">Alias untuk QR/NFC</label>
          <input id="table-new-alias" name="publicAlias"
            pattern="[a-z0-9]+(-[a-z0-9]+)*" maxLength={100}
            required placeholder="a-12"/>
          <label htmlFor="table-new-capacity">Kapasitas kursi</label>
          <input id="table-new-capacity" name="capacity" type="number" min="1" max="100"
            step="1" required defaultValue="2"/>
          <label className="staff-check"><input name="confirm" type="checkbox"
            value="yes" required/>Konfirmasi meja dan alias belum digunakan</label>
          <button className="staff-primary" type="submit">Daftarkan meja</button>
        </form>
      </section>
      <section className="staff-panel">
        <h2>Daftar meja ({result.data.length})</h2>
        <p className="staff-muted">Meja dengan kunjungan aktif tidak dapat diubah.
          Kode/alias QR tidak bisa diubah agar QR yang sudah dicetak tetap berlaku.</p>
        {result.data.length === 0 && <p role="status">Belum ada meja terdaftar.</p>}
        <div className="staff-table-wrap"><table className="staff-table">
          <thead><tr><th scope="col">Meja</th><th scope="col">QR / NFC</th>
            <th scope="col">Status</th><th scope="col">Kapasitas dan tindakan</th></tr></thead>
          <tbody>{result.data.map(table => <tr key={table.id}>
            <td><strong>{table.tableCode}</strong></td>
            <td><code>/t/{table.publicAlias}</code></td>
            <td><span className="staff-status">{table.activeVisit
              ? "KUNJUNGAN AKTIF" : table.status}</span></td>
            <td>{table.activeVisit ? <p>Tutup kunjungan di
              {" "}<Link href="/staff/tables">pengelolaan meja</Link> terlebih dahulu.</p>
              : <form action={updateCafeTable} className="staff-form">
                <input type="hidden" name="tableId" value={table.id}/>
                <label htmlFor={"capacity-" + table.id}>Kapasitas</label>
                <input id={"capacity-" + table.id} name="capacity" type="number"
                  min="1" max="100" step="1" defaultValue={table.capacity} required/>
                <label htmlFor={"status-" + table.id}>Status meja</label>
                <select id={"status-" + table.id} name="status" defaultValue={table.status}>
                  <option value="AVAILABLE">Tersedia</option>
                  <option value="OUT_OF_SERVICE">Tidak dapat digunakan</option>
                </select>
                <label className="staff-check"><input name="confirm" type="checkbox"
                  value="yes" required/>Konfirmasi perubahan</label>
                <button className="staff-secondary" type="submit">Simpan meja</button>
              </form>}</td>
          </tr>)}</tbody>
        </table></div>
      </section>
    </>}
    <p className="staff-footnote">Untuk menjaga riwayat transaksi, meja tidak dapat
      dihapus melalui antarmuka. Alias QR hanya penunjuk lokasi, bukan izin
      memesan. Kode bergabung diberikan waiter pada setiap kunjungan.</p>
  </main>;
}
