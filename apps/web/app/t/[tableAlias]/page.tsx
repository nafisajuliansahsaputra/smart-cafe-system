import Link from "next/link";
import { getTableEntry, TABLE_ALIAS } from "@/lib/guest-server";
import { joinTable } from "../../visit/actions";
import "../../guest.css";

export const dynamic = "force-dynamic";

const messages: Record<string, string> = {
  invalid: "Nama, kode akses meja, atau masukan belum valid.",
  missing: "Sesi atau kode akses meja tidak ditemukan. Minta waiter membuka sesi dan memperbarui kode.",
  conflict: "Sesi meja telah berubah. Minta kode akses baru kepada waiter.",
  expired: "Sesi sudah berakhir. Minta waiter membuka sesi meja.",
  offline: "Koneksi ke sistem belum tersedia. Silakan coba lagi.",
  failed: "Gagal bergabung ke meja. Silakan coba lagi.",
};
type Props = {
  params: Promise<{ tableAlias: string }>;
  searchParams: Promise<{ notice?: string }>;
};

export default async function TableEntry({ params, searchParams }: Props) {
  const { tableAlias } = await params;
  const { notice } = await searchParams;
  const validAlias = TABLE_ALIAS.test(tableAlias);
  const entry = validAlias ? await getTableEntry(tableAlias) : { ok: false as const, status: 404 };
  const message = notice && Object.hasOwn(messages, notice) ? messages[notice] : null;
  return (
    <main className="guest-shell guest-entry">
      <header className="guest-top"><Link className="guest-wordmark" href="/">SMART CAFE / QR</Link></header>
      <p className="guest-eyebrow">SELAMAT DATANG</p>
      <h1>Pesan dari meja, tanpa aplikasi.</h1>
      <p className="guest-subtitle">Pilih menu favoritmu dan pantau pesanan langsung dari browser.</p>
      {message && <p className="guest-alert" role="alert">{message}</p>}
      {!entry.ok && <section className="guest-panel">
        <h2>{entry.status === 404 ? "Meja tidak ditemukan" : "Meja belum bisa diperiksa"}</h2>
        <p>Cek kembali kode QR/NFC atau hubungi staf. Tidak ada akses pemesanan yang diberikan.</p>
      </section>}
      {entry.ok && (
        <section className="guest-panel">
          <div className="guest-table-number">MEJA <strong>{entry.data.tableCode}</strong></div>
          {entry.data.activeVisit ? (
            <>
              <h2>Gabung ke kunjungan meja</h2>
              <p>QR/NFC hanya menunjukkan lokasi meja. Untuk memesan, minta <strong>kode bergabung sementara</strong> dari waiter yang bertugas.</p>
              <form action={joinTable} className="guest-form">
                <input type="hidden" name="alias" value={tableAlias} />
                <label htmlFor="guest-name">Nama panggilan</label>
                <input id="guest-name" name="displayName" type="text" required
                  minLength={1} maxLength={80} autoComplete="nickname" placeholder="Contoh: Raka" />
                <label htmlFor="join-proof">Kode bergabung dari waiter</label>
                <input id="join-proof" name="joinProof" type="password" required
                  minLength={43} maxLength={43} autoComplete="off" spellCheck={false}
                  placeholder="Tempel kode sesi sementara" />
                <button className="guest-primary" type="submit">Gabung dan lihat menu</button>
              </form>
              <p className="guest-footnote">Nomor telepon dan pendaftaran akun tidak diperlukan.</p>
            </>
          ) : (
            <>
              <h2>Meja belum diaktifkan</h2>
              <p>Silakan panggil waiter untuk membuka kunjungan meja sebelum bergabung.</p>
            </>
          )}
        </section>
      )}
      <p className="guest-footnote">Versi pengembangan. Pembayaran yang tersedia untuk pengujian saat ini hanya cash.</p>
    </main>
  );
}
