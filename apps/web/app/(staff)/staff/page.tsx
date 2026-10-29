import Link from "next/link";
import { redirect } from "next/navigation";
import { logoutStaff } from "./actions";
import { canUseOcc, staffIdentity } from "@/lib/staff-server";

export const dynamic = "force-dynamic";

export default async function StaffPage() {
  const current = await staffIdentity();
  if (current.kind === "signed-out") redirect("/staff/login");
  if (current.kind === "unavailable") {
    return (
      <main className="staff-shell">
        <h1>Layanan staf tidak tersedia</h1>
        <p>Identitas staf tidak dapat diverifikasi saat ini. Tidak ada aksi yang diizinkan.</p>
        <Link href="/staff">Coba lagi</Link>
      </main>
    );
  }
  return (
    <main className="staff-shell">
      <header className="staff-header">
        <div>
          <div className="staff-eyebrow">SMART CAFE SYSTEM / STAFF</div>
          <h1>Ruang operasional</h1>
          <p className="staff-muted">Masuk sebagai {current.staff.login}. Peran: {current.staff.roles.join(", ") || "tidak ditetapkan"}.</p>
        </div>
        <form action={logoutStaff}><button type="submit" className="staff-secondary">Keluar</button></form>
      </header>
      {canUseOcc(current.staff.roles) ? (
        <section className="staff-panel">
          <h2>Order Control Center</h2>
          <p>Antrean pesanan aktual dari database, bukan data simulasi. Persetujuan cash tidak berarti sudah dibayar.</p>
          <Link className="staff-link-button" href="/staff/orders">Buka antrean pesanan</Link>
        </section>
      ) : (
        <section className="staff-panel">
          <h2>Belum ada layar untuk peran ini</h2>
          <p>Akun terverifikasi, tetapi antarmuka waiter dan kitchen belum tersedia pada iterasi ini. Anda tidak mendapat akses OCC tanpa peran yang sesuai.</p>
        </section>
      )}
    </main>
  );
}
