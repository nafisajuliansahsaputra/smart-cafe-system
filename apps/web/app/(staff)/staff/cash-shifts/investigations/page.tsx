import { randomUUID } from "node:crypto";
import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import { idr, localTime, staffIdentity } from "@/lib/staff-server";
import { cashVarianceCases, cashVarianceHistory } from "@/lib/cash-variance-server";
import { addVarianceReview } from "./actions";

export const dynamic = "force-dynamic";

const labels: Record<string,string> = {
  REQUEST_RECOUNT: "Minta hitung ulang",
  DOCUMENT_EVIDENCE: "Dokumentasikan bukti",
  ESCALATE: "Eskalasi ke penanggung jawab",
};

export default async function InvestigationsPage({
  searchParams,
}: { searchParams: Promise<{ page?: string; shift?: string; notice?: string }> }) {
  const identity=await staffIdentity();
  if (identity.kind==="signed-out") redirect("/staff/login?status=expired");
  if (identity.kind==="unavailable") return <main className="staff-shell">
    <h1>Investigasi tidak tersedia</h1>
    <p>Identitas staf belum dapat diverifikasi. Tidak ada tindakan yang diizinkan.</p>
    <Link href="/staff">Kembali</Link>
  </main>;
  if (!identity.staff.roles.some(r=>r==="MANAGER" || r==="ADMIN")) notFound();
  const params=await searchParams;
  const pageText=params.page??"0";
  const validPage=/^(0|[1-9][0-9]{0,3})$/.test(pageText) && Number(pageText)<=1000;
  const data=validPage ? await cashVarianceCases(Number(pageText)) : null;
  if (data && !data.ok && data.status===401) redirect("/staff/login?status=expired");
  const selected=params.shift;
  const selectedCase=data?.ok && data.data.items.find(x=>x.shiftId===selected);
  const history=selectedCase ? await cashVarianceHistory(selectedCase.shiftId) : null;
  if (history && !history.ok && history.status===401) redirect("/staff/login?status=expired");

  return <main className="staff-shell">
    <header className="staff-header">
      <div><div className="staff-eyebrow">AUDIT / MANAGER</div>
        <h1>Investigasi selisih kas</h1>
        <p className="staff-muted">Pemeriksaan shift yang telah ditutup dan memiliki
          selisih. Tidak ada perubahan pembayaran ataupun kas final.</p>
      </div>
      <Link href="/staff/cash-shifts">Shift kas</Link>
    </header>
    <nav className="staff-breadcrumb">
      <Link href="/staff">Ruang staf</Link><span>/</span>
      <Link href="/staff/cash-shifts">Shift kas</Link><span>/</span>
      <strong>Investigasi selisih</strong>
    </nav>
    {params.notice && <p role={params.notice==="recorded"?"status":"alert"}
      className="staff-notice">
      {params.notice==="recorded" ? "Tindakan investigasi tersimpan sebagai audit." :
        params.notice==="conflict" ? "Data investigasi berubah. Periksa ulang sebelum mengirim." :
          params.notice==="forbidden" ? "Akses tidak diizinkan." :
            params.notice==="offline" ? "Server belum mengonfirmasi aksi. Jangan ulangi tanpa memeriksa riwayat." :
              params.notice==="missing" ? "Shift tidak ditemukan." :
                "Input tidak valid."}
    </p>}
    {!validPage && <p className="staff-notice" role="alert">Nomor halaman tidak valid.</p>}
    {data && !data.ok && <p className="staff-notice" role="alert">
      Daftar investigasi tidak dapat diverifikasi. Tidak ada status yang diasumsikan.
    </p>}
    {data?.ok && data.data.items.length===0 &&
      <section className="staff-panel"><h2>Tidak ada selisih kas</h2>
        <p>Belum ada shift dengan selisih pada halaman ini.</p></section>}
    {data?.ok && <section className="staff-panel" aria-label="Daftar investigasi selisih kas">
      <h2>Shift dengan selisih</h2>
      {data.data.items.map((item) => <article key={item.shiftId}
        className="staff-panel" aria-label={"Selisih shift "+item.shiftId}>
        <h3>Ditutup {localTime(item.closedAt)}</h3>
        <div className="staff-info-grid">
          <div><small>Seharusnya</small><strong>{idr(item.expectedDrawerIdr)}</strong></div>
          <div><small>Uang dihitung</small><strong>{idr(item.countedCashIdr)}</strong></div>
          <div><small>Selisih</small><strong>{idr(item.differenceIdr)}</strong></div>
        </div>
        <p>Status tindak lanjut terbaru: <strong>
          {item.latestAction ? labels[item.latestAction] : "Belum ditinjau"}</strong>
          {item.latestActionAt ? " · "+localTime(item.latestActionAt) : ""}
        </p>
        <form className="staff-form" action={addVarianceReview}>
          <input name="shiftId" type="hidden" value={item.shiftId}/>
          <input name="idempotencyKey" type="hidden" value={randomUUID()}/>
          <label htmlFor={"action-"+item.shiftId}>Tindak lanjut</label>
          <select name="actionCode" id={"action-"+item.shiftId} required
            defaultValue="REQUEST_RECOUNT">
            {Object.entries(labels).map(([key,label])=>
              <option key={key} value={key}>{label}</option>)}
          </select>
          <label htmlFor={"reason-"+item.shiftId}>Catatan pemeriksaan (10–300 karakter)</label>
          <textarea name="note" id={"reason-"+item.shiftId} rows={3} required
            minLength={10} maxLength={300}
            placeholder="Contoh: minta hitung ulang laci dengan supervisor, cocokan bukti serah-terima"/>
          <label className="staff-check">
            <input name="confirm" type="checkbox" value="yes" required/>
            Saya memahami ini hanya catatan investigasi, bukan koreksi nominal uang
          </label>
          <button type="submit" className="staff-primary">Catat tindak lanjut</button>
        </form>
        <p><Link href={"/staff/cash-shifts/investigations?page="+data.data.page+
          "&shift="+item.shiftId}>Lihat riwayat investigasi</Link></p>
        {selectedCase?.shiftId===item.shiftId && <section
          aria-label="Riwayat investigasi shift">
          <h4>100 catatan terbaru</h4>
          {history && !history.ok && <p role="alert">Riwayat belum dapat dimuat.</p>}
          {history?.ok && history.data.length===0 && <p>Belum ada tindakan tercatat.</p>}
          {history?.ok && <ul>{history.data.map(a=><li key={a.id}>
            <strong>{labels[a.actionCode]}</strong> · {localTime(a.createdAt)}
            <p>{a.note}</p>
          </li>)}</ul>}
        </section>}
      </article>)}
      <nav className="staff-form" aria-label="Halaman investigasi">
        {data.data.page>0 && <Link href={
          "/staff/cash-shifts/investigations?page="+(data.data.page-1)
        }>Halaman sebelumnya</Link>}
        {data.data.hasNext && data.data.page<1000 && <Link href={
          "/staff/cash-shifts/investigations?page="+(data.data.page+1)
        }>Halaman berikutnya</Link>}
      </nav>
    </section>}
    <p className="staff-footnote">Selisih tidak membuktikan kehilangan,
      kecurangan, ataupun uang sudah kembali. Pemeriksaan tidak mengubah
      struk final, pembayaran, shift, kembalian, atau saldo bank.
      Tindak lanjut harus didukung penghitungan fisik dan bukti operasional.</p>
  </main>;
}
