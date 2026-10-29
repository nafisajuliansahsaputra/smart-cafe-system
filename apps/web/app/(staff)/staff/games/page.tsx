import Link from "next/link";
import { LiveRefresh } from "@/components/live-refresh";
import { notFound, redirect } from "next/navigation";
import { canUseWaiter, staffIdentity } from "@/lib/staff-server";
import { staffGames, staffGameLoans } from "@/lib/game-server";
import { createPhysicalGame, registerGameCopy, updateGameLoan } from "./actions";

export const dynamic = "force-dynamic";
const messages: Record<string,string> = {
  updated: "Perubahan permainan berhasil disimpan. Periksa status terbaru.",
  conflict: "Status berubah atau unit tidak tersedia. Segarkan sebelum mencoba lagi.",
  forbidden: "Akses permainan ditolak. Hanya waiter penanggung jawab yang boleh menangani pinjaman.",
  invalid: "Konfirmasi, ID permainan, atau nomor unit tidak valid.",
  offline: "Tidak ada perubahan yang bisa dikonfirmasi. Cek koneksi sebelum mengulangi tindakan.",
};

export default async function StaffGamesPage({searchParams}:{
  searchParams: Promise<{notice?:string}>;
}) {
  const identity=await staffIdentity();
  if(identity.kind==="signed-out") redirect("/staff/login?status=expired");
  if(identity.kind==="unavailable") return <main className="staff-shell">
    <h1>Inventaris belum tersedia</h1><p>Identitas staf belum dapat diverifikasi.</p>
    <Link href="/staff">Kembali</Link></main>;
  if(!canUseWaiter(identity.staff.roles)) notFound();
  const isManager=identity.staff.roles.some(r=>r==="MANAGER"||r==="ADMIN");
  const {notice}=await searchParams;
  const [loans,catalog]=await Promise.all([staffGameLoans(),staffGames()]);
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
