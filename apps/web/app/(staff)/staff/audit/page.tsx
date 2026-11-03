import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import { auditEvents } from "@/lib/audit-server";
import { localTime, staffIdentity } from "@/lib/staff-server";

export const dynamic = "force-dynamic";

function url(page: number, q: string) {
  const params = new URLSearchParams({
    page: String(page),
    ...(q ? { q } : {}),
  });
  return "/staff/audit?" + params;
}

export default async function AuditHistoryPage({
  searchParams,
}: { searchParams: Promise<{ page?: string; q?: string }> }) {
  const identity = await staffIdentity();
  if (identity.kind === "signed-out") redirect("/staff/login?status=expired");
  if (identity.kind === "unavailable") return <main className="staff-shell">
    <h1>Audit belum dapat diverifikasi</h1>
    <p role="alert">Identitas belum dapat dipastikan. Riwayat tidak akan ditampilkan.</p>
    <Link href="/staff">Ruang staf</Link>
  </main>;
  if (!identity.staff.roles.some(r => r === "MANAGER" || r === "ADMIN")) notFound();

  const params = await searchParams;
  const q = typeof params.q === "string" ? params.q.trim() : "";
  const validPage = typeof params.page === "undefined" ||
    /^(0|[1-9][0-9]{0,3})$/.test(params.page);
  const requestedPage = validPage ? Number(params.page ?? "0") : -1;
  const result = await auditEvents(requestedPage, q);
  if (!result.ok && result.status === 401) redirect("/staff/login?status=expired");
  return <main className="staff-shell staff-wide">
    <header className="staff-header">
      <div><div className="staff-eyebrow">SMART CAFE / MANAGER + ADMIN</div>
        <h1>Jejak aktivitas staf</h1>
        <p className="staff-muted">Riwayat perubahan yang sudah tercatat oleh backend.
          Tidak menampilkan informasi pribadi tamu, token, atau detail rahasia.</p>
      </div>
      <Link className="staff-link-button" href="/staff">Ruang operasional</Link>
    </header>
    <nav className="staff-breadcrumb" aria-label="Lokasi halaman">
      <Link href="/staff">Ruang staf</Link><span>/</span><strong>Jejak aktivitas</strong>
    </nav>
    <section className="staff-panel">
      <h2>Telusuri riwayat</h2>
      <form method="GET" className="staff-form">
        <label htmlFor="audit-search">Cari aktivitas, objek, atau staf</label>
        <input id="audit-search" type="search" name="q" defaultValue={q.slice(0,60)}
          pattern="[A-Za-z0-9_-]*" maxLength={60}
          placeholder="Contoh: TABLE_VISIT_OPENED"/>
        <button className="staff-primary" type="submit">Cari aktivitas</button>
        {q && <Link href="/staff/audit">Reset pencarian</Link>}
      </form>
    </section>
    {!result.ok ? <section className="staff-panel" role="alert">
      <h2>Riwayat tidak tersedia</h2>
      <p>{result.status === 400
        ? "Format pencarian atau halaman tidak valid. Gunakan huruf, angka, garis bawah, atau tanda hubung."
        : "API tidak mengonfirmasi riwayat. Tidak ada rekaman contoh yang ditampilkan."}</p>
    </section> : <section className="staff-panel" aria-label="Daftar jejak aktivitas">
      <h2>Aktivitas tercatat</h2>
      <p className="staff-muted">Urut terbaru, {result.data.pageSize} aktivitas per halaman,
        waktu ditampilkan dalam WIB. Data bersifat baca-saja.</p>
      {result.data.items.length === 0 ? <p role="status">Tidak ada aktivitas yang sesuai.</p> :
        <div className="staff-table-wrap"><table className="staff-table">
          <thead><tr><th scope="col">Waktu</th><th scope="col">Operator</th>
            <th scope="col">Aktivitas</th><th scope="col">Objek</th></tr></thead>
          <tbody>{result.data.items.map(event => <tr key={event.id}>
            <td>{localTime(event.occurredAt)}</td>
            <td>{event.actor}</td>
            <td><strong>{event.operation.replaceAll("_", " ")}</strong></td>
            <td><span>{event.resourceType}</span><small style={{ display: "block",
              overflowWrap: "anywhere" }}>{event.resourceId}</small></td>
          </tr>)}</tbody>
        </table></div>}
      <nav className="staff-breadcrumb" aria-label="Halaman aktivitas">
        {result.data.page > 0 &&
          <Link className="staff-link-button" href={url(result.data.page - 1, q)}>
            Halaman sebelumnya
          </Link>}
        <span>Halaman {result.data.page + 1}</span>
        {result.data.hasNext && result.data.page < 1000 &&
          <Link className="staff-link-button" href={url(result.data.page + 1, q)}>
            Halaman berikutnya
          </Link>}
      </nav>
    </section>}
    <p className="staff-footnote">
      Riwayat ini adalah proyeksi baca-saja dari audit server, bukan log aktivitas
      seluruh perangkat. Akses manager/admin diperlukan. Rekaman yang belum tersedia
      tidak boleh disimpulkan sebagai bukti tidak adanya aktivitas.
    </p>
  </main>;
}
