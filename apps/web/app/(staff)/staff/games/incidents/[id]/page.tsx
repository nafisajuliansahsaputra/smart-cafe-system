import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import { adminGameLossEvidence } from "@/lib/game-server";
import { localTime, staffIdentity } from "@/lib/staff-server";

export const dynamic = "force-dynamic";
const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const eventLabels:Record<string,string>={
  GAME_LOSS_REPORTED:"Staf melaporkan unit belum ditemukan",
  GAME_LOSS_DISMISSED:"Manager menolak laporan setelah pemeriksaan",
  GAME_LOSS_CONFIRMED:"Manager mengonfirmasi kehilangan setelah pemeriksaan",
};
const stateLabels:Record<string,string>={
  OPEN:"Menunggu pemeriksaan manager",
  DISMISSED:"Laporan ditolak",
  CONFIRMED_LOST:"Kehilangan dikonfirmasi",
};

/** Evidence is never surfaced to a waiter, guest, cashier, kitchen or
 * unauthenticated browser; backend authorization also enforces the boundary.
 */
export default async function LossEvidencePage({params}:{
  params:Promise<{id:string}>;
}) {
  const current=await staffIdentity();
  if(current.kind==="signed-out")redirect("/staff/login?status=expired");
  if(current.kind==="unavailable")return <main className="staff-shell">
    <h1>Verifikasi staf belum tersedia</h1>
    <p>Data bukti insiden tidak bisa ditampilkan hingga akses terverifikasi.</p>
    <Link href="/staff">Kembali ke dashboard</Link>
  </main>;
  if(!current.staff.roles.some(r=>r==="MANAGER"||r==="ADMIN"))notFound();
  const {id}=await params;
  if(!UUID.test(id))notFound();
  const result=await adminGameLossEvidence(id);
  if(!result.ok && result.status===404)notFound();
  if(!result.ok)return <main className="staff-shell">
    <h1>Riwayat insiden belum tersedia</h1>
    <p role="alert">Catatan aktivitas tidak bisa diverifikasi dari server.
      Jangan mengambil keputusan berdasarkan informasi lama.</p>
    <Link href="/staff/games?istatus=OPEN">Kembali ke laporan kehilangan</Link>
  </main>;
  const {incident,reporterLogin,reviewerLogin,reviewedAt,activity}=result.data;
  return <main className="staff-shell staff-wide">
    <nav className="staff-breadcrumb"><Link href="/staff">Ruang staf</Link>
      <span>/</span><Link href="/staff/games">Permainan</Link>
      <span>/</span><strong>Jejak insiden</strong></nav>
    <header className="staff-header"><div>
      <div className="staff-eyebrow">SMART CAFE / AUDIT PERMAINAN</div>
      <h1>Riwayat laporan unit</h1>
      <p className="staff-muted">Hanya untuk pemeriksaan operasional manager.
        Riwayat ini bukan penetapan kesalahan atau kewajiban pembayaran pelanggan.</p>
    </div></header>
    <section className="staff-panel">
      <h2>Meja {incident.tableCode} · {incident.gameName}</h2>
      <p>Unit fisik: <strong>{incident.copyCode}</strong></p>
      <p>Status: <strong>{stateLabels[incident.status]??incident.status}</strong></p>
      <p>Dilaporkan oleh: <strong>{reporterLogin}</strong> · {localTime(incident.reportedAt)}</p>
      <p>Catatan laporan: {incident.reason}</p>
      {reviewerLogin && reviewedAt && <>
        <p>Diperiksa oleh: <strong>{reviewerLogin}</strong> · {localTime(reviewedAt)}</p>
        <p>Hasil pemeriksaan: {incident.reviewReason}</p>
      </>}
      {!reviewerLogin && <p>Belum ada keputusan manager untuk laporan ini.</p>}
    </section>
    <section className="staff-panel">
      <h2>Jejak aktivitas staf</h2>
      <p className="staff-muted">Urutan event audit berasal dari catatan server.
        Setiap tindakan memiliki ID korelasi untuk penelusuran internal.</p>
      {activity.length===0 && <p role="alert">
        Jejak audit belum tersedia. Verifikasi kembali dengan administrator.</p>}
      {activity.length>0 && <ol>{activity.map(event=><li key={event.id}>
        <p><strong>{eventLabels[event.operation]??event.operation}</strong></p>
        <p>Staf: {event.actorLogin} · {localTime(event.occurredAt)}</p>
        <p>Referensi audit: <code>{event.correlationId}</code></p>
      </li>)}</ol>}
    </section>
    <Link className="staff-link-button" href="/staff/games?istatus=OPEN">
      Kembali ke daftar laporan
    </Link>
  </main>;
}
