import Link from "next/link";
import { LiveRefresh } from "@/components/live-refresh";
import { notFound, redirect } from "next/navigation";
import { logoutStaff } from "../actions";
import { canUseWaiter, staffIdentity } from "@/lib/staff-server";
import { staffTablePage } from "@/lib/staff-table-pages";
import { TableManager } from "./table-manager";

export const dynamic = "force-dynamic";

export default async function StaffTablesPage({ searchParams }: {
  searchParams: Promise<{ q?: string; page?: string }>;
}) {
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
  const query = await searchParams;
  const q = typeof query.q === "string" ? query.q.trim() : "";
  const validPage = typeof query.page === "undefined" ||
    /^(0|[1-9][0-9]{0,3})$/.test(query.page);
  const page = validPage ? Number(query.page ?? "0") : -1;
  const result = await staffTablePage(page, q);
  const pageUrl = (n: number) => "/staff/tables?" +
    new URLSearchParams({ page: String(n), ...(q ? { q } : {}) }).toString();
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
      {identity.staff.roles.some(role => role === "MANAGER" || role === "ADMIN") &&
        <section className="staff-panel">
          <h2>Inventaris fisik meja</h2>
          <p>Daftarkan meja baru, ubah kapasitas dan tandai meja rusak sebagai
            nonaktif tanpa memutus kunjungan yang sedang berlangsung.</p>
          <Link className="staff-link-button" href="/staff/tables/registry">
            Kelola inventaris meja
          </Link>
        </section>}
      <div className="staff-live-line"><LiveRefresh url={`/staff/live?topic=tables`} /></div>
      <form method="GET" className="staff-panel staff-catalog-search">
        <label htmlFor="waiter-table-search">Cari kode meja atau alias QR/NFC</label>
        <div>
          <input id="waiter-table-search" name="q" type="search"
            defaultValue={q.slice(0, 60)} maxLength={60}
            pattern="[A-Za-z0-9_-]*" placeholder="Contoh: VIP-03" />
          <button className="staff-secondary" type="submit">Cari meja</button>
          {q && <Link href="/staff/tables">Reset</Link>}
        </div>
      </form>
      <form method="GET"><input type="hidden" name="page" value={String(Math.max(0, page))} />
        {q && <input type="hidden" name="q" value={q} />}
        <button className="staff-secondary" type="submit">Segarkan status meja</button></form>
      {!result.ok ? (
        <section className="staff-panel" role="alert">
          <h2>Daftar meja belum dapat dimuat</h2>
          <p>{result.status === 400
            ? "Pencarian atau halaman tidak valid. Gunakan huruf, angka, garis bawah atau tanda hubung."
            : result.status === 403 ? "Akses ditolak oleh backend."
            : "API tidak tersedia atau hasil tidak valid. Tidak ada tindakan yang diizinkan."}</p>
        </section>
      ) : (
        <>
          <p className="staff-muted" role="status">
            {result.data.total} meja cocok · halaman {result.data.page + 1} ·
            maksimum {result.data.pageSize} meja per halaman
          </p>
          <TableManager tables={result.data.items} />
          <nav className="staff-breadcrumb" aria-label="Halaman operasional meja">
            {result.data.page > 0 &&
              <Link className="staff-link-button" href={pageUrl(result.data.page - 1)}>
                Halaman sebelumnya
              </Link>}
            <span>Halaman {result.data.page + 1}</span>
            {result.data.hasNext && result.data.page < 1000 &&
              <Link className="staff-link-button" href={pageUrl(result.data.page + 1)}>
                Halaman berikutnya
              </Link>}
          </nav>
        </>
      )}
      <p className="staff-footnote">Kode QR hanya menunjukkan identitas meja, bukan izin memesan. Kode bergabung bersifat sementara dan hanya diberikan langsung kepada tamu yang berada di meja.</p>
    </main>
  );
}
