import Link from "next/link";
import { redirect } from "next/navigation";
import { logoutStaff } from "./actions";
import { canUseOcc, canUseKitchen, canUseWaiter, staffIdentity } from "@/lib/staff-server";

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
      ) : null}
      {canUseKitchen(current.staff.roles) && (
        <section className="staff-panel">
          <h2>Kitchen Display</h2>
          <p>Antrean masak hanya berisi pesanan yang sudah diterima dapur. Ubah status menyiapkan dan siap diantar melalui API.</p>
          <Link className="staff-link-button" href="/staff/kitchen">Buka dashboard dapur</Link>
        </section>
      )}
      {canUseWaiter(current.staff.roles) && (
        <section className="staff-panel">
          <h2>Table Management</h2>
          <p>Buka atau tutup kunjungan meja, serta buat kode bergabung sementara untuk tamu. Kode hanya ditampilkan setelah tindakan yang diotorisasi.</p>
          <Link className="staff-link-button" href="/staff/tables">Kelola meja</Link>
        </section>
      )}
      {canUseWaiter(current.staff.roles) && (
        <section className="staff-panel">
          <h2>Waiter &amp; Cash</h2>
          <p>Daftar pengantaran dan penerimaan uang tunai, dibatasi berdasarkan meja yang ditangani. Cash baru lunas setelah dicatat oleh server.</p>
          <Link className="staff-link-button" href="/staff/waiter">Buka tugas waiter</Link>
        </section>
      )}
      {!canUseOcc(current.staff.roles) && !canUseKitchen(current.staff.roles) && !canUseWaiter(current.staff.roles) && (
        <section className="staff-panel">
          <h2>Tidak ada layar untuk peran ini</h2>
          <p>Akun terverifikasi tetapi belum memiliki izin untuk antarmuka operasional.</p>
        </section>
      )}
    </main>
  );
}
