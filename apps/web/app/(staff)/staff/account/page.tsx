import Link from "next/link";
import { redirect } from "next/navigation";
import { staffIdentity } from "@/lib/staff-server";
import { changeOwnPassword } from "./actions";

export const dynamic="force-dynamic";

const notices:Record<string,string>={
  invalid:"Kata sandi tidak valid. Gunakan 16–72 karakter ASCII tanpa spasi untuk kata sandi baru.",
  mismatch:"Kata sandi baru dan konfirmasi tidak cocok.",
  credentials:"Kata sandi saat ini salah.",
  limited:"Terlalu banyak percobaan. Coba lagi nanti.",
  conflict:"Kata sandi baru harus berbeda dari kata sandi lama.",
  offline:"Server belum memastikan perubahan. Jangan berasumsi kata sandi telah berubah.",
};

export default async function StaffAccountPage({
  searchParams,
}:{searchParams:Promise<{notice?:string}>}) {
  const identity=await staffIdentity();
  if(identity.kind==="signed-out")redirect("/staff/login?status=expired");
  if(identity.kind==="unavailable")return <main className="staff-shell">
    <h1>Pengaturan akun belum tersedia</h1>
    <p>Identitas belum dapat diverifikasi. Tidak ada perubahan kata sandi yang diasumsikan.</p>
    <Link href="/staff">Kembali ke staf</Link>
  </main>;
  const {notice}=await searchParams;
  return <main className="staff-shell staff-login">
    <nav className="staff-breadcrumb">
      <Link href="/staff">Ruang staf</Link><span>/</span><strong>Keamanan akun</strong>
    </nav>
    <section className="staff-panel">
      <h1>Ganti kata sandi</h1>
      <p className="staff-muted">
        Masuk sebagai {identity.staff.login}. Setelah perubahan berhasil,
        seluruh sesi pada perangkat lain maupun perangkat ini akan dicabut.
      </p>
      {notice && Object.hasOwn(notices,notice) &&
        <p role="alert" className="staff-notice">{notices[notice]}</p>}
      <form action={changeOwnPassword} className="staff-form">
        <label htmlFor="old-password">Kata sandi saat ini</label>
        <input id="old-password" type="password" name="currentPassword"
          required maxLength={128} autoComplete="current-password"/>
        <label htmlFor="new-password">Kata sandi baru</label>
        <input id="new-password" type="password" name="newPassword"
          required minLength={16} maxLength={72} autoComplete="new-password"
          aria-describedby="password-advice"/>
        <p id="password-advice" className="staff-muted">
          Minimal 16 karakter, maksimal 72 karakter ASCII tanpa spasi.
          Jangan gunakan kembali kata sandi lama.
        </p>
        <label htmlFor="repeat-password">Ulangi kata sandi baru</label>
        <input id="repeat-password" type="password" name="confirmation"
          required minLength={16} maxLength={72} autoComplete="new-password"/>
        <label className="staff-check">
          <input type="checkbox" name="confirm" value="yes" required/>
          Saya paham semua sesi akan berakhir dan harus login ulang
        </label>
        <button className="staff-primary" type="submit">Ganti kata sandi dan keluar</button>
      </form>
    </section>
  </main>;
}
