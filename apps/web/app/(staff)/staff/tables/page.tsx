import Link from "next/link";
import { LiveRefresh } from "@/components/live-refresh";
import { notFound, redirect } from "next/navigation";
import { logoutStaff } from "../actions";
import { canUseWaiter, staffIdentity, staffTables } from "@/lib/staff-server";
import { TableManager } from "./table-manager";

export const dynamic = "force-dynamic";

export default async function StaffTablesPage() {
  const identity = await staffIdentity();
  if (identity.kind === "signed-out") redirect("/staff/login?status=expired");
  if (identity.kind === "unavailable") return (
    <main className="staff-shell">
      <h1>Layanan meja belum dapat diverifikasi</h1>
      <p>Tidak ada tindakan yang dapat dilakukan saat ini. Periksa koneksi API.</p>
      <Link href="/staff/tables">Coba lagi</Link>
    </main>
  );
  if (!canUseWaiter(identity.staff.roles)) notFound();
  const result = await staffTables();
  if (!result.ok && result.status === 401) redirect("/staff/login?status=expired");
  return (
    <main className="staff-shell staff-wide">
      <header className="staff-header">
        <div>
          <div className="staff-eyebrow">SMART CAFE / TABLE MANAGEMENT</div>
          <h1>Kelola meja</h1>
          <p className="staff-muted">Buka sesi tamu, putar kode bergabung sementara, atau tutup meja setelah semua transaksi selesai.</p>
        </div>
        <form action={logoutStaff}><button className="staff-secondary" type="submit">Keluar</button></form>
      </header>
      <nav className="staff-breadcrumb">
        <Link href="/staff">Ruang staf</Link><span>/</span><strong>Meja</strong>
      </nav>
      <div className="staff-live-line"><LiveRefresh url={`/staff/live?topic=tables`} /></div>
      <form method="GET"><button className="staff-secondary" type="submit">Segarkan status meja</button></form>
      {!result.ok ? (
        <section className="staff-panel" role="alert">
          <h2>Daftar meja belum dapat dimuat</h2>
          <p>{result.status === 403 ? "Akses ditolak oleh backend." :
            "API tidak tersedia atau hasil tidak valid. Tidak ada tindakan yang diizinkan."}</p>
        </section>
      ) : (
        <TableManager tables={result.data} />
      )}
      <p className="staff-footnote">Kode QR hanya menunjukkan identitas meja, bukan izin memesan. Kode bergabung bersifat sementara dan hanya diberikan langsung kepada tamu yang berada di meja.</p>
    </main>
  );
}
