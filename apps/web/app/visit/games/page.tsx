import Link from "next/link";
import { guestIdentity } from "@/lib/guest-server";
import { guestGames, myGameLoans } from "@/lib/game-server";
import { requestPhysicalGame } from "./actions";

export const dynamic = "force-dynamic";
const messages: Record<string,string> = {
  requested: "Permintaan permainan sudah tercatat. Tunggu waiter mengantarkan unit fisiknya.",
  conflict: "Permintaan permainan tersebut sudah aktif atau sedang diproses.",
  invalid: "Permintaan tidak valid. Periksa pilihan dan konfirmasi.",
  offline: "Server tidak bisa mengonfirmasi permintaan. Periksa daftar pinjaman sebelum mencoba lagi.",
};
const statuses: Record<string,string> = {
  REQUESTED: "Menunggu waiter",
  ASSIGNED: "Unit disiapkan",
  IN_USE: "Sedang dipinjam",
  RETURNED: "Sudah dikembalikan",
  CANCELLED: "Dibatalkan",
};

export default async function GuestGamesPage({ searchParams }: {
  searchParams: Promise<{ notice?: string }>;
}) {
  const identity = await guestIdentity();
  if (identity.kind === "signed-out") return (
    <main className="guest-shell"><h1>Belum bergabung ke meja</h1>
      <p>Gabung lewat kode yang diberikan waiter sebelum meminjam permainan.</p>
      <Link href="/visit">Kembali</Link></main>
  );
  if (identity.kind === "unavailable") return (
    <main className="guest-shell"><h1>Sesi belum dapat diverifikasi</h1>
      <p>Permintaan permainan dinonaktifkan sampai koneksi pulih.</p>
      <Link href="/visit/games">Coba lagi</Link></main>
  );
  const { notice } = await searchParams;
  const [catalog, current] = await Promise.all([guestGames(), myGameLoans()]);
  const active = current.ok ? new Set(current.data
    .filter((l) => ["REQUESTED","ASSIGNED","IN_USE"].includes(l.status))
    .map((l) => l.gameId)) : new Set<string>();
  return (
    <main className="guest-shell guest-wide">
      <header className="guest-top">
        <Link className="guest-wordmark" href="/visit">SMART CAFE</Link>
        <nav className="guest-nav"><Link href="/visit">Menu</Link>
          <Link href="/visit/orders">Pesanan saya</Link></nav>
      </header>
      <div className="guest-hero">
        <p className="guest-eyebrow">MEJA {identity.visit.tableCode} / GAME LIBRARY</p>
        <h1>Pinjam permainan fisik.</h1>
        <p className="guest-subtitle">Pilih UNO atau board game, lalu tunggu waiter menyiapkan dan menyerahkan unit fisiknya. Tidak ada biaya peminjaman pada versi ini.</p>
      </div>
      {notice && Object.hasOwn(messages, notice) &&
        <p className="guest-alert" role="status">{messages[notice]}</p>}
      <section className="guest-panel">
        <h2>Pinjaman saya</h2>
        {!current.ok ? <p role="alert">Daftar pinjaman belum bisa diverifikasi. Jangan mengulangi permintaan sampai status tersedia.</p> :
          current.data.length===0 ? <p>Belum ada permintaan permainan.</p> :
          <ul>{current.data.map((l) => <li key={l.id}>
            <strong>{l.gameName}</strong> · {statuses[l.status] ?? l.status}
            {l.copyCode && <span> · Unit {l.copyCode}</span>}
          </li>)}</ul>}
        <form method="GET"><button className="guest-secondary" type="submit">Segarkan pinjaman</button></form>
      </section>
      <section className="guest-category" data-catalog-status={catalog.ok ? "ok" : String(catalog.status)}>
        <h2>Koleksi permainan</h2>
        {!catalog.ok && <p role="alert">Katalog permainan belum dapat dimuat.</p>}
        {catalog.ok && catalog.data.length===0 && <p>Belum ada permainan tersedia di katalog.</p>}
        {catalog.ok && <div className="guest-menu-grid">{catalog.data.map((game) => (
          <article className="guest-panel guest-menu-card" key={game.id}>
            <div><h3>{game.name}</h3>
              {game.description && <p>{game.description}</p>}
              <p>{game.availableCopies} unit tersedia saat terakhir diperiksa</p></div>
            <form action={requestPhysicalGame}>
              <input type="hidden" name="gameId" value={game.id}/>
              <label><input type="checkbox" name="confirm" value="yes" required/>
                Saya akan menjaga dan mengembalikan permainan melalui waiter</label>
              <button className="guest-secondary" disabled={active.has(game.id) || !current.ok || game.availableCopies===0}>
                {active.has(game.id) ? "Permintaan aktif" : "Ajukan pinjam"}
              </button>
            </form>
          </article>))}</div>}
      </section>
      <p className="guest-footnote">Permintaan tidak otomatis berarti permainan sudah diserahkan. Ketersediaan dan nomor unit diperiksa ulang oleh waiter saat penugasan.</p>
    </main>
  );
}
