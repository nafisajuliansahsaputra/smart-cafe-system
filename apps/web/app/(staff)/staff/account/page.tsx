import Link from "next/link";
import { redirect } from "next/navigation";
import { staffIdentity } from "@/lib/staff-server";
import { changeStaffPassword } from "../actions";

export const dynamic = "force-dynamic";

const notices: Record<string, string> = {
  invalid: "Data tidak valid. Gunakan kata sandi baru 16–72 karakter ASCII tanpa spasi.",
  mismatch: "Konfirmasi kata sandi baru tidak sama.",
  reused: "Kata sandi baru harus berbeda dari kata sandi saat ini.",
  incorrect: "Kata sandi saat ini tidak benar.",
  limited: "Terlalu banyak percobaan. Tunggu sekitar 15 menit.",
  offline: "Server tidak dapat mengonfirmasi perubahan. Coba lagi setelah layanan pulih.",
};

export default async function AccountPage({ searchParams }: {
  searchParams: Promise<{ notice?: string }>;
}) {
  const identity = await staffIdentity();
  if (identity.kind === "signed-out") redirect("/staff/login?status=expired");
  if (identity.kind === "unavailable") return (
    <main className="staff-shell">
      <h1>Keamanan akun tidak tersedia</h1>
      <p>Identitas tidak dapat diverifikasi. Tidak ada perubahan kata sandi.</p>
      <Link href="/staff">Kembali</Link>
    </main>
  );
  const { notice } = await searchParams;
  const message = notice && Object.hasOwn(notices, notice) ? notices[notice] : undefined;
  return (
    <main className="staff-shell">
      <div className="staff-eyebrow">SMART CAFE SYSTEM / KEAMANAN</div>
      <nav className="staff-breadcrumb" aria-label="Navigasi akun">
        <Link href="/staff">Ruang staf</Link><span>/</span><strong>Kata sandi</strong>
      </nav>
      <section className="staff-panel">
        <h1>Ganti kata sandi</h1>
        <p className="staff-muted">
          Akun {identity.staff.login}. Masukkan kata sandi lama dan kata sandi baru yang unik.
          Setelah berhasil, seluruh sesi, termasuk perangkat ini, harus login ulang.
        </p>
        {message && <p className="staff-notice" role="status">{message}</p>}
        <form className="staff-form" action={changeStaffPassword}>
          <label htmlFor="current-password">Kata sandi saat ini</label>
          <input id="current-password" type="password" name="currentPassword"
            autoComplete="current-password" minLength={8} maxLength={128} required />
          <label htmlFor="new-password">Kata sandi baru (16–72 karakter ASCII tanpa spasi)</label>
          <input id="new-password" type="password" name="newPassword"
            autoComplete="new-password" minLength={16} maxLength={72} required />
          <label htmlFor="confirm-password">Ulangi kata sandi baru</label>
          <input id="confirm-password" type="password" name="confirmPassword"
            autoComplete="new-password" minLength={16} maxLength={72} required />
          <button className="staff-primary" type="submit">Ganti kata sandi dan keluar dari semua sesi</button>
        </form>
      </section>
      <p className="staff-footnote">
        Perubahan hanya berlaku setelah backend mengonfirmasi. Jangan gunakan kata sandi bersama.
        Pemulihan akun yang terlupa belum tersedia.
      </p>
    </main>
  );
}
