import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import {
  localTime, sandboxReconciliationPage, sandboxReviewClassifications,
  sandboxReviewEnabled, staffIdentity, type SandboxClassification,
} from "@/lib/staff-server";

export const dynamic="force-dynamic";

function pageLink(page:number,classification:SandboxClassification) {
  return "/staff/reconciliation?page="+page+"&classification="+classification;
}

export default async function SandboxReconciliationPage({
  searchParams,
}:{searchParams:Promise<{page?:string;classification?:string}>}) {
  const identity=await staffIdentity();
  if(identity.kind==="signed-out")redirect("/staff/login?status=expired");
  if(identity.kind==="unavailable")return <main className="staff-shell">
    <h1>Antrean tidak dapat diverifikasi</h1>
    <p>Identitas staf tidak tersedia. Tidak ada event yang disetujui.</p>
    <Link href="/staff">Kembali</Link>
  </main>;
  if(!identity.staff.roles.includes("ADMIN")||!sandboxReviewEnabled())notFound();
  const query=await searchParams;
  const filter=query.classification??"ALL";
  const pageText=query.page??"0";
  if(!sandboxReviewClassifications.some((v)=>v===filter)||
    !/^(0|[1-9][0-9]{0,3})$/.test(pageText)||Number(pageText)>1000)notFound();
  const classification=filter as SandboxClassification;
  const page=Number(pageText);
  const result=await sandboxReconciliationPage(page,classification);
  if(!result.ok&&result.status===401)redirect("/staff/login?status=expired");

  return <main className="staff-shell">
    <header className="staff-header">
      <div><div className="staff-eyebrow">ADMIN / SANDBOX ONLY</div>
        <h1>Antrean bukti pembayaran simulasi</h1>
        <p className="staff-muted">Catatan klaim FAKE yang belum diverifikasi. Bukan mutasi bank.</p>
      </div>
      <Link href="/staff">Ruang staf</Link>
    </header>
    <nav className="staff-breadcrumb"><Link href="/staff">Ruang staf</Link>
      <span>/</span><strong>Rekonsiliasi simulasi</strong></nav>
    <section className="staff-panel">
      <h2>Hanya baca</h2>
      <p className="staff-notice" role="status">
        Event SUCCEEDED di layar ini hanya klaim palsu dari simulator.
        Tidak ada otorisasi pembayaran, tombol pelunasan, refund, atau penerbitan struk.
      </p>
      <form method="GET" className="staff-form">
        <label htmlFor="sandbox-classification">Filter klasifikasi</label>
        <select id="sandbox-classification" name="classification" defaultValue={classification}>
          {sandboxReviewClassifications.map((value)=>
            <option value={value} key={value}>{value}</option>)}
        </select>
        <button className="staff-primary" type="submit">Tampilkan bukti</button>
      </form>
    </section>
    {!result.ok && <section className="staff-panel" role="alert">
      Data tidak dapat diverifikasi (status {result.status}).
      Jangan memperlakukan klaim event sebagai pembayaran nyata.
    </section>}
    {result.ok && <>
      <section className="staff-panel" aria-label="Ringkasan klaim sintetis">
        <h2>Ringkasan bukti tercatat</h2>
        <div className="staff-info-grid">
          <div><small>Semua bukti FAKE</small><strong>{result.data.totalEvidence}</strong></div>
          <div><small>Klaim sukses, belum diverifikasi</small>
            <strong>{result.data.successClaims}</strong></div>
          <div><small>Anomali diklasifikasikan</small>
            <strong>{result.data.flaggedEvidence}</strong></div>
        </div>
      </section>
      <section className="staff-panel">
        <h2>Daftar event {classification} · Halaman {page+1}</h2>
        {result.data.items.length===0&&<p role="status">
          Tidak ada event simulasi untuk halaman dan filter ini.</p>}
        {result.data.items.length>0&&<div className="staff-table-wrap">
          <table className="staff-table">
            <thead><tr>
              <th scope="col">Diterima (WIB)</th>
              <th scope="col">Klasifikasi</th>
              <th scope="col">Klaim</th>
              <th scope="col">Nominal dilaporkan</th>
              <th scope="col">Referensi FAKE</th>
              <th scope="col">Status tercatat</th>
            </tr></thead>
            <tbody>{result.data.items.map((event)=><tr key={event.id}>
              <td>{localTime(event.receivedAt)}</td>
              <td><strong>{event.classification}</strong></td>
              <td>{event.method} · {event.claimedState}</td>
              <td>{event.reportedAmountIdr.toLocaleString("id-ID")} {event.currency}</td>
              <td><code>{event.reference}</code></td>
              <td>{event.currentPaymentState??"Tidak diketahui"}</td>
            </tr>)}</tbody>
          </table>
        </div>}
        <div className="staff-form">
          {page>0&&<Link className="staff-secondary" href={pageLink(page-1,classification)}>
            Halaman sebelumnya</Link>}
          {result.data.hasNext&&page<1000&&
            <Link className="staff-secondary" href={pageLink(page+1,classification)}>
              Halaman berikutnya</Link>}
        </div>
      </section>
    </>}
    <p className="staff-footnote">
      Data hanya untuk analisis di sandbox dan tidak dapat diedit dari layar ini.
      Klaim keberhasilan pembayaran tidak sama dengan verifikasi provider.
      Total dan halaman adalah dua pembacaan database yang mungkin berubah selama refresh.
    </p>
  </main>;
}
