import Link from "next/link";
import { redirect } from "next/navigation";
import { LiveRefresh } from "@/components/live-refresh";
import { managerGameLossAlerts } from "@/lib/game-server";
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
  const isManager=current.staff.roles.some(r=>r==="MANAGER"||r==="ADMIN");
  const lossAlerts=isManager ? await managerGameLossAlerts() : null;
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
      <section className="staff-panel">
        <h2>Keamanan akun</h2>
        <p>Ganti kata sandi sendiri. Setelah berhasil, semua sesi pada perangkat lain akan dicabut.</p>
        <Link className="staff-link-button" href="/staff/account">Ganti kata sandi</Link>
      </section>
      {isManager && <section className="staff-panel">
        <h2>Manajemen staf</h2>
        <p>Lihat akun, nonaktifkan akses staf biasa dan lihat perubahan lewat audit. Akun admin/manager dilindungi.</p>
        <Link className="staff-link-button" href="/staff/team">Kelola staf</Link>
      </section>}
      {isManager && <section className="staff-panel" aria-labelledby="manager-loss-alert-title">
        <h2 id="manager-loss-alert-title">Pemeriksaan laporan kehilangan</h2>
        <p className="staff-muted">
          Antrean insiden permainan fisik yang menunggu keputusan manager.
          Informasi disinkronkan melalui event internal, tanpa data pribadi pelanggan.
        </p>
        <LiveRefresh url="/staff/live?topic=games"/>
        {!lossAlerts?.ok && <p role="alert">
          Status laporan kehilangan belum dapat diverifikasi. Buka daftar laporan
          untuk memeriksa sebelum mengambil keputusan.
        </p>}
        {lossAlerts?.ok && lossAlerts.data.openCount===0 &&
          <p role="status">Tidak ada laporan kehilangan yang menunggu pemeriksaan.</p>}
        {lossAlerts?.ok && lossAlerts.data.openCount>0 && <>
          <p role="status" className="staff-notice">
            {lossAlerts.data.openCount} laporan kehilangan memerlukan pemeriksaan manager.
          </p>
          <ul>{lossAlerts.data.recent.map((item,i)=><li key={`${item.copyCode}-${item.reportedAt}-${i}`}>
            <strong>Meja {item.tableCode} · {item.gameName}</strong>
            {" · Unit "}{item.copyCode}
            {" · Dilaporkan "}{new Intl.DateTimeFormat("id-ID",{
              dateStyle:"medium",timeStyle:"short",timeZone:"Asia/Jakarta",
            }).format(new Date(item.reportedAt))}
          </li>)}</ul>
          {lossAlerts.data.openCount>lossAlerts.data.recent.length &&
            <p className="staff-muted">Masih ada laporan lain dalam antrean.</p>}
        </>}
        <Link className="staff-link-button" href="/staff/games?istatus=OPEN#loss-incidents">
          Buka laporan menunggu pemeriksaan
        </Link>
      </section>}
      {canUseOcc(current.staff.roles) ? (
        <section className="staff-panel">
          <h2>Order Control Center</h2>
          <p>Antrean pesanan aktual dari database, bukan data simulasi. Persetujuan cash tidak berarti sudah dibayar.</p>
          <Link className="staff-link-button" href="/staff/orders">Buka antrean pesanan</Link>
          <p><Link href="/staff/reports">Lihat laporan cash harian</Link></p>
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
      {canUseWaiter(current.staff.roles) && (
        <section className="staff-panel">
          <h2>Peminjaman permainan fisik</h2>
          <p>Permintaan UNO dan board game, alokasi unit berseri, serah-terima, dan pengembalian.</p>
          <Link className="staff-link-button" href="/staff/games">Kelola permainan</Link>
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
