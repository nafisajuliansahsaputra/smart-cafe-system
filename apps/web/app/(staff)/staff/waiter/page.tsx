import Link from "next/link";
import { LiveRefresh } from "@/components/live-refresh";
import { randomUUID } from "node:crypto";
import { notFound, redirect } from "next/navigation";
import { logoutStaff } from "../actions";
import { captureCashTender, markOrderServed } from "../fulfillment-actions";
import { canUseWaiter, idr, localTime, staffIdentity } from "@/lib/staff-server";
import { waiterTaskPage } from "@/lib/staff-waiter-task-pages";

export const dynamic = "force-dynamic";

const notices: Record<string, string> = {
  updated: "Perintah telah diterima server. Periksa status terbaru di bawah.",
  invalid: "Konfirmasi, jumlah uang, atau identitas pesanan tidak valid.",
  conflict: "Tindakan tidak sesuai status pesanan atau uang yang diterima tidak cukup. Periksa kembali.",
  forbidden: "Akses ditolak. Waiter hanya boleh menangani meja yang menjadi tanggung jawabnya.",
  offline: "Server tidak dapat mengonfirmasi aksi. Jangan mencatat penerimaan uang ulang tanpa memeriksa status.",
  failed: "Perintah gagal. Periksa status terbaru sebelum mencoba lagi.",
};

export default async function WaiterPage({
  searchParams,
}: {
  searchParams: Promise<{ notice?: string; page?: string }>;
}) {
  const identity = await staffIdentity();
  if (identity.kind === "signed-out") redirect("/staff/login?status=expired");
  if (identity.kind === "unavailable") return (
    <main className="staff-shell"><h1>Layanan waiter tidak tersedia</h1>
      <p>Identitas staf tidak dapat diverifikasi. Tidak ada aksi yang diizinkan.</p><Link href="/staff">Kembali</Link></main>
  );
  if (!canUseWaiter(identity.staff.roles)) notFound();
  const { notice, page: rawPage } = await searchParams;
  const message = notice && Object.hasOwn(notices, notice) ? notices[notice] : null;
  const validPage = rawPage === undefined || /^(0|[1-9][0-9]{0,3})$/.test(rawPage);
  const page = validPage ? Number(rawPage ?? "0") : -1;
  const result = await waiterTaskPage(page);
  if (!result.ok && result.status === 401) redirect("/staff/login?status=expired");
  return (
    <main className="staff-shell staff-wide">
      <header className="staff-header">
        <div><div className="staff-eyebrow">SMART CAFE / WAITER</div><h1>Pengantaran &amp; Cash</h1>
          <p className="staff-muted">Tagihan cash belum lunas ditampilkan lebih dulu, lalu pesanan siap diantar. Tugas tetap dibatasi menurut kewenangan staf.</p></div>
        <form action={logoutStaff}><button className="staff-secondary">Keluar</button></form>
      </header>
      <nav className="staff-breadcrumb"><Link href="/staff">Ruang staf</Link><span>/</span><strong>Waiter</strong><span>/</span>
        <Link href="/staff/games">Pinjaman permainan</Link></nav>
      <div className="staff-live-line"><LiveRefresh url={`/staff/live?topic=waiter`} /></div>
      <form method="GET">
        {page > 0 && <input type="hidden" name="page" value={String(page)} />}
        <button className="staff-secondary" type="submit">Segarkan tugas</button>
      </form>
      {message && <p className="staff-notice" role="status">{message}</p>}
      {!result.ok && <section className="staff-panel"><h2>Daftar tugas tidak tersedia</h2>
        <p role="alert">{result.status === 400
          ? "Halaman tugas tidak valid. Gunakan nomor halaman yang tersedia."
          : result.status === 403
          ? "Akses ke daftar tugas ditolak oleh backend."
          : "Tidak ada status yang bisa dikonfirmasi. Cek koneksi dan hak akses."}</p></section>}
      {result.ok && result.data.items.length === 0 && <section className="staff-panel">
        <h2>Tidak ada tugas pengantaran</h2><p>Belum ada pesanan siap diantar atau menunggu cash.</p></section>}
      {result.ok && (
        <div className="staff-task-grid">
          {result.data.items.map((task) => (
            <article className="staff-panel staff-task-card" key={task.orderId}
              aria-labelledby={`waiter-task-${task.orderId}`}>
              <div className="staff-task-head"><h2 id={`waiter-task-${task.orderId}`}>Meja {task.tableCode}</h2>
                <div><span className="staff-status">{task.orderStatus}</span> / <strong className={task.paymentStatus === "PAID" ? "staff-paid" : "staff-unpaid"}>{task.paymentStatus}</strong></div>
              </div>
              <p className="staff-muted">Dibuat {localTime(task.createdAt)} WIB</p>
              <ul className="staff-item-list">{task.lines.map((line,index) => (
                <li key={index}><strong>{line.quantity} × {line.name}</strong>
                  {line.variantLabel && <small>{line.variantLabel}</small>}
                  {line.notes && <small>Catatan: {line.notes}</small>}
                </li>
              ))}</ul>
              <p className="staff-task-total">Tagihan: <strong>{idr(task.totalIdr)}</strong></p>
              {task.orderStatus === "READY" && (
                <form action={markOrderServed} className="staff-form">
                  <input type="hidden" name="orderId" value={task.orderId}/>
                  <label className="staff-check"><input type="checkbox" name="confirm" value="yes" required/>
                    Pesanan sudah diserahkan langsung ke meja</label>
                  <button className="staff-primary">Tandai sudah diantar</button>
                </form>
              )}
              {task.orderStatus === "SERVED" && task.paymentStatus === "AWAITING_CASH" && (
                <form action={captureCashTender} className="staff-form">
                  <input type="hidden" name="paymentIntentId" value={task.paymentIntentId}/>
                  <input type="hidden" name="idempotencyKey" value={randomUUID()}/>
                  <label htmlFor={`cash-${task.orderId}`}>Uang tunai diterima (rupiah)</label>
                  <input id={`cash-${task.orderId}`} name="amountReceivedIdr" inputMode="numeric"
                    type="number" min={task.totalIdr} max="999999999999" step="1" required
                    placeholder={String(task.totalIdr)}/>
                  <p className="staff-muted">Tagihan {idr(task.totalIdr)}. Kembalian dihitung di server setelah penerimaan terkonfirmasi.</p>
                  <label className="staff-check"><input name="confirm" type="checkbox" value="yes" required/>
                    Saya telah menerima dan menghitung uang fisik dari pelanggan</label>
                  <button type="submit" className="staff-primary">Catat uang diterima</button>
                </form>
              )}
              {task.orderStatus === "SERVED" && task.paymentStatus === "PAID" && (
                <p className="staff-paid"><strong>LUNAS</strong> · pembayaran sudah tercatat di backend.
                  {task.receivedIdr !== null && task.changeGivenIdr !== null && (
                    <span className="staff-change">Diterima: {idr(task.receivedIdr)} · Kembalian: {idr(task.changeGivenIdr)}</span>
                  )}
                </p>
              )}
            </article>
          ))}
        </div>
      )}
      {result.ok && (
        <nav className="staff-breadcrumb" aria-label="Halaman tugas waiter">
          {result.data.page > 0 &&
            <Link className="staff-link-button" href={`/staff/waiter?page=${result.data.page - 1}`}>
              Halaman sebelumnya
            </Link>}
          <span role="status">
            {result.data.total} tugas cocok · halaman {result.data.page + 1} · 25 per halaman
          </span>
          {result.data.hasNext && result.data.page < 1000 &&
            <Link className="staff-link-button" href={`/staff/waiter?page=${result.data.page + 1}`}>
              Halaman berikutnya
            </Link>}
        </nav>
      )}
      <p className="staff-footnote">Mode pengembangan. Kebijakan cash-at-handover masih provisional. Uang sungguhan, QRIS/VA, dan rekonsiliasi produksi belum diizinkan.</p>
    </main>
  );
}
