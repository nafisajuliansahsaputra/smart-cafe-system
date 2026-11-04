import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import {
  idr, localTime, sandboxPendingAttemptFilters,sandboxPendingAttemptPage,
  sandboxReviewEnabled,staffIdentity,type SandboxPendingAttemptFilter,
} from "@/lib/staff-server";

export const dynamic="force-dynamic";

function link(page:number,filter:SandboxPendingAttemptFilter) {
  return "/staff/reconciliation/attempts?page="+page+
    "&filter="+encodeURIComponent(filter);
}

export default async function SandboxUnresolvedAttempts({
  searchParams,
}:{searchParams:Promise<{page?:string;filter?:string}>}) {
  const identity=await staffIdentity();
  if(identity.kind==="signed-out")redirect("/staff/login?status=expired");
  if(identity.kind==="unavailable")return <main className="staff-shell">
    <h1>Status investigasi tidak tersedia</h1>
    <p>Identitas admin tidak dapat diverifikasi. Tidak ada perubahan pembayaran.</p>
    <Link href="/staff">Kembali</Link>
  </main>;
  if(!identity.staff.roles.includes("ADMIN")||!sandboxReviewEnabled())notFound();

  const params=await searchParams;
  const filter=params.filter??"ALL";
  const pageText=params.page??"0";
  if(!sandboxPendingAttemptFilters.some(f=>f===filter)||
    !/^(0|[1-9][0-9]{0,3})$/.test(pageText)||
    Number(pageText)>1000)notFound();
  const selected=filter as SandboxPendingAttemptFilter;
  const page=Number(pageText);
  const result=await sandboxPendingAttemptPage(page,selected);
  if(!result.ok&&result.status===401)redirect("/staff/login?status=expired");

  return <main className="staff-shell">
    <header className="staff-header">
      <div>
        <div className="staff-eyebrow">ADMIN / SANDBOX / READ ONLY</div>
        <h1>Payment attempt belum selesai</h1>
        <p className="staff-muted">Monitoring referensi FAKE yang masih menggantung.</p>
      </div>
      <Link href="/staff/reconciliation">Bukti event sandbox</Link>
    </header>
    <section className="staff-panel">
      <h2>Peringatan rekonsiliasi</h2>
      <p className="staff-notice" role="status">
        Lewat tanggal kedaluwarsa bukan bukti pembayaran gagal. Klaim SUCCEEDED
        juga bukan bukti uang diterima. Semua percobaan di sini tetap belum
        terverifikasi dan tidak dapat dilunasi dari halaman ini.
      </p>
      <form method="GET" className="staff-form">
        <label htmlFor="pending-filter">Filter tindak lanjut</label>
        <select id="pending-filter" name="filter" defaultValue={selected}>
          <option value="ALL">Semua yang belum selesai</option>
          <option value="OVERDUE">Lewat waktu, belum terverifikasi</option>
          <option value="SUCCESS_CLAIM">Ada klaim sukses belum terverifikasi</option>
          <option value="FLAGGED_CLAIM">Ada klaim anomali</option>
          <option value="NO_EVIDENCE">Belum ada event</option>
        </select>
        <button className="staff-primary" type="submit">Tampilkan</button>
      </form>
    </section>
    {!result.ok&&<section className="staff-panel" role="alert">
      Antrean belum dapat diverifikasi (status {result.status}).
      Jangan mengubah status pembayaran berdasarkan tampilan ini.
    </section>}
    {result.ok&&<section className="staff-panel">
      <h2>Daftar {selected} · Halaman {page+1}</h2>
      <p className="staff-muted">Diperiksa: {localTime(result.data.checkedAt)} WIB.</p>
      {result.data.items.length===0&&<p role="status">
        Tidak ada percobaan belum selesai untuk filter ini.
      </p>}
      {result.data.items.length>0&&<div className="staff-table-wrap">
        <table className="staff-table">
          <thead><tr>
            <th scope="col">Referensi</th>
            <th scope="col">Metode / nominal</th>
            <th scope="col">Kedaluwarsa simulasi</th>
            <th scope="col">Bukti tidak tepercaya</th>
            <th scope="col">Status tercatat</th>
          </tr></thead>
          <tbody>{result.data.items.map(a=><tr key={a.id}>
            <td><code>{a.providerReference}</code></td>
            <td>{a.method} · {idr(a.amountIdr)}</td>
            <td>
              {localTime(a.expiresAt)} WIB
              {a.overdue&&<strong> · LEWAT WAKTU, BUTUH REVIEW</strong>}
            </td>
            <td>
              {a.evidenceCount} event,
              {" "}{a.successClaims} klaim sukses,
              {" "}{a.flaggedClaims} anomali
              {a.lastEvidenceAt&&<p className="staff-muted">
                Terakhir: {localTime(a.lastEvidenceAt)} WIB
              </p>}
            </td>
            <td>{a.attemptState} / {a.paymentState}</td>
          </tr>)}</tbody>
        </table>
      </div>}
      <div className="staff-form">
        {page>0&&<Link className="staff-secondary" href={link(page-1,selected)}>
          Halaman sebelumnya
        </Link>}
        {result.data.hasNext&&page<1000&&
          <Link className="staff-secondary" href={link(page+1,selected)}>
            Halaman berikutnya
          </Link>}
      </div>
    </section>}
    <p className="staff-footnote">
      Data ini bukan hasil verifikasi dari provider pembayaran. Tidak tersedia
      aksi expire, retry, approve, paid, refund atau release kitchen.
    </p>
  </main>;
}
