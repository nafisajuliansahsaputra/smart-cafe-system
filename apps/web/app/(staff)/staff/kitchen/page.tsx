import Link from "next/link";
import { LiveRefresh } from "@/components/live-refresh";
import { notFound, redirect } from "next/navigation";
import { logoutStaff } from "../actions";
import { markKitchenReady, startKitchenTicket } from "../fulfillment-actions";
import { canUseKitchen, kitchenTickets, staffIdentity } from "@/lib/staff-server";

export const dynamic = "force-dynamic";

const notices: Record<string, string> = {
  updated: "Perintah diproses. Status terbaru telah diambil dari server.",
  invalid: "Periksa kembali konfirmasi dan identitas tiket.",
  conflict: "Tiket sudah berubah. Segarkan daftar untuk melihat status terbaru.",
  forbidden: "Aksi ditolak oleh backend.",
  offline: "Server belum bisa memverifikasi tindakan. Jangan mengasumsikan pesanan berubah.",
  failed: "Perintah gagal. Tidak ada keberhasilan yang dikonfirmasi.",
};

export default async function KitchenPage({
  searchParams,
}: {
  searchParams: Promise<{ notice?: string }>;
}) {
  const identity = await staffIdentity();
  if (identity.kind === "signed-out") redirect("/staff/login?status=expired");
  if (identity.kind === "unavailable") return (
    <main className="staff-shell"><h1>Layanan dapur tidak tersedia</h1>
      <p>Identitas tidak dapat diverifikasi. Perintah dinonaktifkan.</p><Link href="/staff">Kembali</Link></main>
  );
  if (!canUseKitchen(identity.staff.roles)) notFound();
  const { notice } = await searchParams;
  const message = notice && Object.hasOwn(notices, notice) ? notices[notice] : null;
  const tickets = await kitchenTickets();
  if (!tickets.ok && tickets.status === 401) redirect("/staff/login?status=expired");
  return (
    <main className="staff-shell staff-wide">
      <header className="staff-header">
        <div><div className="staff-eyebrow">SMART CAFE / KITCHEN</div>
          <h1>Kitchen Display</h1><p className="staff-muted">Hanya tiket yang diterima dapur. Status berasal dari database.</p></div>
        <form action={logoutStaff}><button className="staff-secondary">Keluar</button></form>
      </header>
      <nav className="staff-breadcrumb"><Link href="/staff">Ruang staf</Link><span>/</span><strong>Dapur</strong></nav>
      <div className="staff-live-line"><LiveRefresh url={`/staff/live?topic=kitchen`} /></div>
      <form method="GET"><button className="staff-secondary" type="submit">Segarkan antrean</button></form>
      {message && <p className="staff-notice" role="status">{message}</p>}
      {!tickets.ok && <section className="staff-panel"><h2>Tiket tidak dapat dimuat</h2>
        <p role="alert">API tidak tersedia atau tidak mengizinkan akses. Tidak ada perintah yang dikirim.</p></section>}
      {tickets.ok && tickets.data.length === 0 && <section className="staff-panel">
        <h2>Dapur sedang kosong</h2><p>Belum ada pesanan yang diterima untuk disiapkan.</p></section>}
      {tickets.ok && (
        <div className="staff-task-grid">
          {tickets.data.map((ticket) => (
            <article className="staff-panel staff-task-card" key={ticket.id}>
              <div className="staff-task-head"><h2>Meja {ticket.tableCode}</h2><span className="staff-status">{ticket.status}</span></div>
              <ul className="staff-item-list">{ticket.lines.map((line, index) => (
                <li key={index}><strong>{line.quantity} × {line.name}</strong>
                  {line.variantLabel && <small>{line.variantLabel}</small>}
                  {line.notes && <small>Catatan: {line.notes}</small>}
                </li>
              ))}</ul>
              {ticket.status === "ACCEPTED" && (
                <form action={startKitchenTicket} className="staff-form">
                  <input type="hidden" name="ticketId" value={ticket.id}/>
                  <label className="staff-check"><input type="checkbox" name="confirm" value="yes" required />
                    Mulai menyiapkan pesanan ini</label>
                  <button className="staff-primary" type="submit">Mulai masak</button>
                </form>
              )}
              {ticket.status === "PREPARING" && (
                <form action={markKitchenReady} className="staff-form">
                  <input type="hidden" name="ticketId" value={ticket.id}/>
                  <label className="staff-check"><input type="checkbox" name="confirm" value="yes" required />
                    Semua item sudah disiapkan</label>
                  <button className="staff-primary" type="submit">Siap diantar</button>
                </form>
              )}
              {ticket.status === "READY" && <p className="staff-muted">Menunggu waiter mengambil pesanan.</p>}
            </article>
          ))}
        </div>
      )}
      <p className="staff-footnote">Perubahan tiket dipantau otomatis; tombol Segarkan tetap tersedia. Tidak tersedia akses pembayaran atau nomor telepon pelanggan.</p>
    </main>
  );
}
