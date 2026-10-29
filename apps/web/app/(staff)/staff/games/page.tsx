import Link from "next/link";
import { LiveRefresh } from "@/components/live-refresh";
import { notFound, redirect } from "next/navigation";
import { canUseWaiter, localTime, staffIdentity } from "@/lib/staff-server";
import { searchAdminGameLossIncidents, searchAdminGameConditionHistory, searchAdminGameCopies, staffGames, staffGameLoans } from "@/lib/game-server";
import { createPhysicalGame, recoverLostGameCopy, registerGameCopy, reportMissingGame, reviewMissingGame, updateGameCopyCondition, updateGameLoan } from "./actions";

export const dynamic = "force-dynamic";
const messages: Record<string,string> = {
  updated: "Perubahan permainan berhasil disimpan. Periksa status terbaru.",
  conflict: "Status berubah atau unit tidak tersedia. Segarkan sebelum mencoba lagi.",
  forbidden: "Akses permainan ditolak. Hanya waiter penanggung jawab yang boleh menangani pinjaman.",
  invalid: "Konfirmasi, ID permainan, atau nomor unit tidak valid.",
  offline: "Tidak ada perubahan yang bisa dikonfirmasi. Cek koneksi sebelum mengulangi tindakan.",
};

export default async function StaffGamesPage({searchParams}:{
  searchParams: Promise<{
    notice?:string;q?:string;condition?:string;page?:string;
    hq?:string;hchange?:string;hpage?:string;
    iq?:string;istatus?:string;ipage?:string;
  }>;
}) {
  const identity=await staffIdentity();
  if(identity.kind==="signed-out") redirect("/staff/login?status=expired");
  if(identity.kind==="unavailable") return <main className="staff-shell">
    <h1>Inventaris belum tersedia</h1><p>Identitas staf belum dapat diverifikasi.</p>
    <Link href="/staff">Kembali</Link></main>;
  if(!canUseWaiter(identity.staff.roles)) notFound();
  const isManager=identity.staff.roles.some(r=>r==="MANAGER"||r==="ADMIN");
  const {
    notice,q:rawQuery,condition:rawCondition,page:rawPage,
    hq:rawHistoryQuery,hchange:rawHistoryChange,hpage:rawHistoryPage,
    iq:rawIncidentQuery,istatus:rawIncidentStatus,ipage:rawIncidentPage,
  }=await searchParams;
  const query=typeof rawQuery==="string"?rawQuery.trim().slice(0,80):"";
  const condition=typeof rawCondition==="string" &&
    ["AVAILABLE","DAMAGED","LOST","RETIRED"].includes(rawCondition)?rawCondition:"";
  const pageIndex=typeof rawPage==="string" && /^(0|[1-9][0-9]{0,3})$/.test(rawPage)
    ? Math.min(2000,Number(rawPage)):0;
  const historyQuery=typeof rawHistoryQuery==="string"?
    rawHistoryQuery.trim().slice(0,80):"";
  const historyChange=typeof rawHistoryChange==="string" &&
    ["AVAILABLE","DAMAGED"].includes(rawHistoryChange)?rawHistoryChange:"";
  const historyPage=typeof rawHistoryPage==="string" &&
    /^(0|[1-9][0-9]{0,3})$/.test(rawHistoryPage)?
    Math.min(2000,Number(rawHistoryPage)):0;
  const incidentQuery=typeof rawIncidentQuery==="string"?
    rawIncidentQuery.trim().slice(0,80):"";
  const incidentStatus=typeof rawIncidentStatus==="string" &&
    ["OPEN","DISMISSED","CONFIRMED_LOST"].includes(rawIncidentStatus)?
    rawIncidentStatus:"";
  const incidentPage=typeof rawIncidentPage==="string" &&
    /^(0|[1-9][0-9]{0,3})$/.test(rawIncidentPage)?
    Math.min(2000,Number(rawIncidentPage)):0;
  const urlFor=(copyPage:number,auditPage:number,copyQ=query,copyCondition=condition,
                historyQ=historyQuery,historyTo=historyChange,
                casePage=incidentPage,caseQ=incidentQuery,caseStatus=incidentStatus)=>
    `/staff/games?${new URLSearchParams({
      q:copyQ,condition:copyCondition,page:String(copyPage),
      hq:historyQ,hchange:historyTo,hpage:String(auditPage),
      iq:caseQ,istatus:caseStatus,ipage:String(casePage),
    })}`;
  const incidentLink=(index:number,search=incidentQuery,state=incidentStatus)=>
    urlFor(pageIndex,historyPage,query,condition,historyQuery,historyChange,
      index,search,state);
  const [loans,catalog,copies,history,incidents]=await Promise.all([
    staffGameLoans(),staffGames(),
    isManager ? searchAdminGameCopies(query,condition,pageIndex) : Promise.resolve(null),
    isManager ? searchAdminGameConditionHistory(historyQuery,historyChange,historyPage)
      : Promise.resolve(null),
    isManager ? searchAdminGameLossIncidents(incidentQuery,incidentStatus,incidentPage)
      : Promise.resolve(null),
  ]);
  return <main className="staff-shell staff-wide">
    <header className="staff-header"><div>
      <div className="staff-eyebrow">SMART CAFE / PHYSICAL GAMES</div>
      <h1>Peminjaman permainan</h1>
      <p className="staff-muted">Serahkan unit yang benar-benar ada, verifikasi kode pada unit saat menyerahkan dan menerima kembali.</p>
    </div></header>
    <nav className="staff-breadcrumb"><Link href="/staff">Ruang staf</Link><span>/</span><strong>Permainan</strong></nav>
    <LiveRefresh url="/staff/live?topic=games" />
    <form method="GET"><button className="staff-secondary" type="submit">Segarkan pinjaman</button></form>
    {notice && Object.hasOwn(messages,notice) &&
      <p className="staff-notice" role="status">{messages[notice]}</p>}
    {!loans.ok && <section className="staff-panel" role="alert">
      <h2>Daftar pinjaman tidak tersedia</h2><p>Jangan menyerahkan unit tanpa konfirmasi status server.</p>
    </section>}
    {loans.ok && loans.data.length===0 && <section className="staff-panel">
      <h2>Belum ada pinjaman aktif</h2><p>Permintaan baru akan tampil otomatis jika koneksi live tersedia.</p>
    </section>}
    {loans.ok && <div className="staff-task-grid">{loans.data.map(l=>(
      <article className="staff-panel staff-task-card" key={l.id}>
        <div className="staff-task-head"><h2>Meja {l.tableCode} · {l.gameName}</h2>
          <strong className="staff-status">{l.status}</strong></div>
        {l.copyCode && <p>Unit: <strong>{l.copyCode}</strong></p>}
        {l.status==="RETURN_PENDING" &&
          <p role="status">Pelanggan meminta pengambilan. Pastikan unit sudah diterima dan diperiksa sebelum menyelesaikan.</p>}
        {l.status==="REQUESTED" && <>
          <form action={updateGameLoan} className="staff-form">
            <input type="hidden" name="loanId" value={l.id}/>
            <input type="hidden" name="operation" value="assign"/>
            <label className="staff-check"><input type="checkbox" name="confirm" value="yes" required/>
              Sudah memeriksa ketersediaan unit fisik</label>
            <button className="staff-primary">Alokasikan unit</button>
          </form>
          <form action={updateGameLoan} className="staff-form">
            <input type="hidden" name="loanId" value={l.id}/>
            <input type="hidden" name="operation" value="cancel"/>
            <label className="staff-check"><input type="checkbox" name="confirm" value="yes" required/>
              Tidak dapat memenuhi permintaan ini</label>
            <button className="staff-secondary">Batalkan permintaan</button>
          </form>
        </>}
        {(l.status==="ASSIGNED"||l.status==="IN_USE"||l.status==="RETURN_PENDING") && <form action={updateGameLoan} className="staff-form">
          <input type="hidden" name="loanId" value={l.id}/>
          <input type="hidden" name="operation" value={l.status==="ASSIGNED"?"handover":"return"}/>
          <label htmlFor={`game-unit-${l.id}`}>Masukkan kode pada unit fisik</label>
          <input id={`game-unit-${l.id}`} name="copyCode" required maxLength={80}
            placeholder={l.copyCode??"Kode unit"} autoComplete="off"/>
          <label className="staff-check"><input type="checkbox" name="confirm" value="yes" required/>
            {l.status==="ASSIGNED" ? "Unit telah diserahkan langsung ke pelanggan" :
              "Unit diterima kembali dan sudah diperiksa kondisinya"}</label>
          <button className="staff-primary">
            {l.status==="ASSIGNED"?"Konfirmasi serah-terima":"Tandai dikembalikan"}
          </button>
        </form>}
        {(l.status==="ASSIGNED"||l.status==="IN_USE"||l.status==="RETURN_PENDING") &&
          <form action={reportMissingGame} className="staff-form">
            <input type="hidden" name="loanId" value={l.id}/>
            <label htmlFor={`loss-reason-${l.id}`}>Laporan unit belum ditemukan</label>
            <input id={`loss-reason-${l.id}`} name="reason" required
              minLength={5} maxLength={300} placeholder="Jelaskan pemeriksaan fisik"/>
            <label className="staff-check"><input type="checkbox" name="confirm"
              value="yes" required/>
              Ini hanya laporan. Unit belum dikembalikan dan masih menjadi tugas aktif
            </label>
            <button className="staff-secondary">Laporkan unit hilang</button>
          </form>}
        {(l.status==="IN_USE"||l.status==="RETURN_PENDING") && (
          <form action={updateGameLoan} className="staff-form">
            <input type="hidden" name="loanId" value={l.id}/>
            <input type="hidden" name="operation" value="return-damaged"/>
            <label htmlFor={`damage-code-${l.id}`}>Kode unit rusak/tidak lengkap</label>
            <input id={`damage-code-${l.id}`} name="copyCode"
              required maxLength={80} autoComplete="off" placeholder="Kode unit"/>
            <label htmlFor={`damage-reason-${l.id}`}>Temuan pemeriksaan (wajib)</label>
            <input id={`damage-reason-${l.id}`} name="reason"
              minLength={5} maxLength={300} required
              placeholder="Contoh: dua kartu hilang"/>
            <label className="staff-check">
              <input type="checkbox" name="confirm" value="yes" required/>
              Unit fisik sudah diterima dan harus dikarantina, bukan dipinjamkan lagi
            </label>
            <button className="staff-danger">Terima & karantina unit</button>
          </form>
        )}
      </article>
    ))}</div>}
    <section className="staff-panel">
      <h2>Ketersediaan permainan</h2>
      {!catalog.ok ? <p>Katalog belum dapat dimuat.</p> :
        <ul>{catalog.data.map(g=><li key={g.id}>
          <strong>{g.name}</strong> · {g.availableCopies} unit tersedia
          {isManager && <form action={registerGameCopy} className="staff-form">
            <input type="hidden" name="gameId" value={g.id}/>
            <label htmlFor={`copy-${g.id}`}>Kode unit baru untuk {g.name}</label>
            <input id={`copy-${g.id}`} name="copyCode" maxLength={80} required placeholder="UNO-001"/>
            <button className="staff-secondary">Daftarkan unit</button>
          </form>}
        </li>)}</ul>}
    </section>
    {isManager && <section className="staff-panel">
      <h2>Laporan unit belum ditemukan</h2>
      <p className="staff-muted">Verifikasi fisik dan kondisi unit sebelum memutuskan.
        Konfirmasi kehilangan tidak menagih pelanggan. Unit berstatus LOST tidak
        boleh dipinjamkan kembali.</p>
      <form method="GET" action="/staff/games" className="staff-form" role="search">
        <input type="hidden" name="q" value={query}/>
        <input type="hidden" name="condition" value={condition}/>
        <input type="hidden" name="page" value={pageIndex}/>
        <input type="hidden" name="hq" value={historyQuery}/>
        <input type="hidden" name="hchange" value={historyChange}/>
        <input type="hidden" name="hpage" value={historyPage}/>
        <label htmlFor="game-incident-search">
          Cari laporan berdasarkan unit, game, meja, pelapor atau catatan
        </label>
        <input id="game-incident-search" name="iq" defaultValue={incidentQuery}
          maxLength={80} placeholder="Kode unit, meja, atau isi laporan"/>
        <label htmlFor="game-incident-status">Filter status laporan</label>
        <select id="game-incident-status" name="istatus" defaultValue={incidentStatus}>
          <option value="">Semua status</option>
          <option value="OPEN">Menunggu pemeriksaan</option>
          <option value="DISMISSED">Ditolak</option>
          <option value="CONFIRMED_LOST">Kehilangan dikonfirmasi</option>
        </select>
        <button className="staff-secondary" type="submit">Cari laporan</button>
        <Link href={incidentLink(0,"","")}>Reset laporan</Link>
      </form>
      {incidents && !incidents.ok && <p role="alert">
        Laporan kehilangan belum bisa dimuat. Periksa koneksi.</p>}
      {incidents?.ok && incidents.data.openCount>0 &&
        <p role="status" className="staff-notice">
          {incidents.data.openCount} laporan kehilangan menunggu pemeriksaan manager.
        </p>}
      {incidents?.ok && incidents.data.items.length===0 &&
        <p>{incidentQuery||incidentStatus||incidentPage>0?
          "Tidak ada laporan yang sesuai.":"Tidak ada laporan unit hilang."}</p>}
      {incidents?.ok && <p className="staff-muted">
        Halaman laporan {incidents.data.page+1} · Maksimal 25 insiden per halaman
      </p>}
      {incidents?.ok && <div className="staff-task-grid">
        {incidents.data.items.map(incident=><article className="staff-panel staff-task-card"
          key={incident.id}>
          <h3>Meja {incident.tableCode} · {incident.gameName}</h3>
          <p>Unit: <strong>{incident.copyCode}</strong></p>
          <p>Status laporan: <strong>{incident.status}</strong></p>
          <p>Laporan staf: {incident.reason}</p>
          {incident.reviewReason && <p>Hasil pemeriksaan: {incident.reviewReason}</p>}
          {incident.status==="OPEN" && <>
            <form action={reviewMissingGame} className="staff-form">
              <input type="hidden" name="incidentId" value={incident.id}/>
              <input type="hidden" name="decision" value="DISMISS"/>
              <label htmlFor={`dismiss-loss-${incident.id}`}>Alasan menolak laporan</label>
              <input id={`dismiss-loss-${incident.id}`} name="reason"
                required minLength={5} maxLength={300}/>
              <label className="staff-check">
                <input type="checkbox" name="confirm" value="yes" required/>
                Hasil pemeriksaan memastikan laporan ini perlu ditolak
              </label>
              <button className="staff-secondary">Tolak laporan kehilangan</button>
            </form>
            <form action={reviewMissingGame} className="staff-form">
              <input type="hidden" name="incidentId" value={incident.id}/>
              <input type="hidden" name="decision" value="CONFIRM_LOST"/>
              <label htmlFor={`confirm-loss-code-${incident.id}`}>
                Verifikasi kode unit yang hilang
              </label>
              <input id={`confirm-loss-code-${incident.id}`} name="copyCode"
                required maxLength={80} autoComplete="off"/>
              <label htmlFor={`confirm-loss-note-${incident.id}`}>
                Alasan konfirmasi kehilangan
              </label>
              <input id={`confirm-loss-note-${incident.id}`} name="reason"
                required minLength={5} maxLength={300}/>
              <label className="staff-check">
                <input type="checkbox" name="confirm" value="yes" required/>
                Sudah verifikasi unit fisik, saya setuju menandai copy LOST tanpa denda
              </label>
              <button className="staff-danger">Konfirmasi unit hilang</button>
            </form>
          </>}
        </article>)}
      </div>}
      {incidents?.ok && <nav aria-label="Navigasi halaman laporan kehilangan"
        className="staff-breadcrumb">
        {incidents.data.page>0 && <Link href={incidentLink(incidents.data.page-1)}>
          Laporan sebelumnya
        </Link>}
        {incidents.data.hasNext && <Link href={incidentLink(incidents.data.page+1)}>
          Laporan berikutnya
        </Link>}
      </nav>}
    </section>}
    {isManager && <section className="staff-panel">
      <h2>Kondisi unit fisik</h2>
      <p className="staff-muted">Hanya unit AVAILABLE tanpa pinjaman aktif yang boleh dikarantina.
        Unit DAMAGED harus diperiksa ulang sebelum diaktifkan. Unit LOST yang ditemukan
        wajib dikarantina sebagai DAMAGED lebih dahulu, bukan langsung tersedia.
        Tidak ada denda otomatis.</p>
      <form method="GET" action="/staff/games" className="staff-form" role="search">
        <input type="hidden" name="iq" value={incidentQuery}/>
        <input type="hidden" name="istatus" value={incidentStatus}/>
        <input type="hidden" name="ipage" value={incidentPage}/>
        <input type="hidden" name="hq" value={historyQuery}/>
        <input type="hidden" name="hchange" value={historyChange}/>
        <input type="hidden" name="hpage" value={historyPage}/>
        <label htmlFor="game-copy-search">Cari unit atau permainan</label>
        <input id="game-copy-search" name="q" defaultValue={query} maxLength={80}
          placeholder="Kode unit atau nama permainan"/>
        <label htmlFor="game-copy-condition">Filter kondisi unit</label>
        <select id="game-copy-condition" name="condition" defaultValue={condition}>
          <option value="">Semua kondisi</option>
          <option value="AVAILABLE">AVAILABLE</option>
          <option value="DAMAGED">DAMAGED</option>
          <option value="LOST">LOST</option>
          <option value="RETIRED">RETIRED</option>
        </select>
        <button className="staff-secondary" type="submit">Cari unit</button>
        <Link href={urlFor(0,historyPage,"","")}>Reset pencarian</Link>
      </form>
      {copies && !copies.ok && <p role="alert">Inventaris unit tidak tersedia.
        Tidak ada tindakan yang dapat dikonfirmasi.</p>}
      {copies?.ok && copies.data.items.length===0 && <p>Tidak ada unit yang sesuai.
        Coba kata kunci atau kondisi lain.</p>}
      {copies?.ok && <p className="staff-muted">
        Halaman {copies.data.page+1} · Maksimal 25 unit per halaman
      </p>}
      {copies?.ok && <div className="staff-task-grid">
        {copies.data.items.map(copy=><article className="staff-panel staff-task-card" key={copy.id}>
          <h3>{copy.gameName} · {copy.copyCode}</h3>
          <strong className="staff-status">{copy.condition}</strong>
          {(copy.condition==="AVAILABLE" || copy.condition==="DAMAGED") &&
            <form action={updateGameCopyCondition} className="staff-form">
              <input type="hidden" name="copyId" value={copy.id}/>
              <input type="hidden" name="condition"
                value={copy.condition==="AVAILABLE"?"DAMAGED":"AVAILABLE"}/>
              <label htmlFor={`copy-reason-${copy.id}`}>
                {copy.condition==="AVAILABLE"?"Alasan karantina":"Catatan pemeriksaan ulang"}
              </label>
              <input id={`copy-reason-${copy.id}`} name="reason" required
                minLength={5} maxLength={300} placeholder="Deskripsi pemeriksaan"/>
              <label className="staff-check">
                <input type="checkbox" name="confirm" value="yes" required/>
                {copy.condition==="AVAILABLE"
                  ? "Saya sudah memastikan unit tidak sedang dipinjam"
                  : "Saya sudah memeriksa langsung dan unit layak dipinjam lagi"}
              </label>
              <button className={copy.condition==="AVAILABLE"?"staff-danger":"staff-primary"}>
                {copy.condition==="AVAILABLE"?"Karantina unit":"Aktifkan kembali"}
              </button>
            </form>}
          {copy.condition==="LOST" &&
            <form action={recoverLostGameCopy} className="staff-form">
              <input type="hidden" name="copyId" value={copy.id}/>
              <label htmlFor={`found-code-${copy.id}`}>
                Kode fisik unit yang ditemukan kembali
              </label>
              <input id={`found-code-${copy.id}`} name="copyCode" required
                maxLength={80} autoComplete="off" placeholder="Verifikasi kode dari unit"/>
              <label htmlFor={`found-note-${copy.id}`}>Catatan lokasi dan kondisi penemuan</label>
              <input id={`found-note-${copy.id}`} name="reason" required
                minLength={5} maxLength={300} placeholder="Lokasi ditemukan dan kondisi awal"/>
              <label className="staff-check"><input type="checkbox"
                name="confirm" value="yes" required/>
                Unit benar-benar ditemukan dan akan masuk karantina DAMAGED.
                Ini bukan aktivasi stok, dan laporan kehilangan lama tetap tersimpan
              </label>
              <button className="staff-secondary">Temukan &amp; karantina unit</button>
            </form>}
          {copy.condition==="RETIRED" &&
            <p>Unit telah dipensiunkan dan tidak dapat diaktifkan melalui alur ini.</p>}
        </article>)}
      </div>}
      {copies?.ok && <nav aria-label="Navigasi halaman inventaris"
        className="staff-breadcrumb">
        {copies.data.page>0 && <Link href={urlFor(copies.data.page-1,historyPage)}>
          Halaman sebelumnya
        </Link>}
        {copies.data.hasNext && <Link href={urlFor(copies.data.page+1,historyPage)}>
          Halaman berikutnya
        </Link>}
      </nav>}
    </section>}
    {isManager && <section className="staff-panel">
      <h2>Riwayat pemeriksaan unit</h2>
      <p className="staff-muted">Cari catatan perubahan kondisi lama di seluruh database.
        Catatan hanya untuk operasional, bukan penetapan tanggung jawab atau denda pelanggan.</p>
      <form method="GET" action="/staff/games" className="staff-form" role="search">
        <input type="hidden" name="iq" value={incidentQuery}/>
        <input type="hidden" name="istatus" value={incidentStatus}/>
        <input type="hidden" name="ipage" value={incidentPage}/>
        <input type="hidden" name="q" value={query}/>
        <input type="hidden" name="condition" value={condition}/>
        <input type="hidden" name="page" value={pageIndex}/>
        <label htmlFor="game-audit-search">Cari catatan, staf, nama game atau kode unit</label>
        <input id="game-audit-search" name="hq" defaultValue={historyQuery}
          maxLength={80} placeholder="Contoh: kartu rusak"/>
        <label htmlFor="game-audit-change">Filter hasil pemeriksaan</label>
        <select id="game-audit-change" name="hchange" defaultValue={historyChange}>
          <option value="">Semua perubahan</option>
          <option value="DAMAGED">Menjadi DAMAGED</option>
          <option value="AVAILABLE">Menjadi AVAILABLE</option>
        </select>
        <button type="submit" className="staff-secondary">Cari riwayat</button>
        <Link href={urlFor(pageIndex,0,query,condition,"","")}>Reset riwayat</Link>
      </form>
      {history && !history.ok && <p role="alert">Riwayat pemeriksaan belum bisa dimuat.
        Periksa koneksi sebelum mengambil keputusan.</p>}
      {history?.ok && history.data.items.length===0 && <p>Riwayat pemeriksaan tidak ditemukan.</p>}
      {history?.ok && <p className="staff-muted">
        Halaman riwayat {history.data.page+1} · Maksimal 25 catatan per halaman
      </p>}
      {history?.ok && <ul>{history.data.items.map(item=><li key={item.id}>
        <strong>{item.gameName} · {item.copyCode}</strong>
        {" · "}{item.beforeCondition} → {item.afterCondition}
        {" · "}{localTime(item.recordedAt)}
        {" · Staf: "}{item.actorLogin}
        <p>Catatan: {item.reason}</p>
      </li>)}</ul>}
      {history?.ok && <nav aria-label="Navigasi halaman riwayat pemeriksaan"
        className="staff-breadcrumb">
        {history.data.page>0 && <Link href={urlFor(pageIndex,history.data.page-1)}>
          Riwayat sebelumnya
        </Link>}
        {history.data.hasNext && <Link href={urlFor(pageIndex,history.data.page+1)}>
          Riwayat berikutnya
        </Link>}
      </nav>}
    </section>}
    {isManager && <section className="staff-panel"><h2>Tambahkan jenis permainan</h2>
      <form action={createPhysicalGame} className="staff-form">
        <label htmlFor="new-game-name">Nama permainan</label>
        <input id="new-game-name" name="name" required maxLength={120}/>
        <label htmlFor="new-game-description">Keterangan (opsional)</label>
        <input id="new-game-description" name="description" maxLength={500}/>
        <button className="staff-primary">Simpan permainan</button>
      </form>
    </section>}
    <p className="staff-footnote">Tidak ada biaya/deposit otomatis. Meja tidak dapat ditutup selama ada permintaan, alokasi, atau permainan yang belum dikembalikan. Ketersediaan dicek ulang pada server.</p>
  </main>;
}
