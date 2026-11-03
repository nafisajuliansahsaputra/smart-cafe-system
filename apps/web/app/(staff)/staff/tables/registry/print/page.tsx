import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import { staffIdentity } from "@/lib/staff-server";
import { tableRegistry } from "@/lib/table-registry-server";
import { QrPrintStudio } from "./qr-studio";

export const dynamic = "force-dynamic";

export default async function PrintableTableQrs({ searchParams }: {
  searchParams: Promise<{ page?: string; q?: string }>;
}) {
  const identity = await staffIdentity();
  if (identity.kind === "signed-out") redirect("/staff/login?status=expired");
  if (identity.kind === "unavailable") return <main className="staff-shell">
    <h1>Studio QR belum tersedia</h1>
    <p role="alert">Identitas staf tidak dapat diverifikasi. Tidak ada QR yang diterbitkan.</p>
    <Link href="/staff/tables/registry">Kembali ke inventaris</Link>
  </main>;
  if (!identity.staff.roles.some(role => role === "MANAGER" || role === "ADMIN")) notFound();

  const params = await searchParams;
  const q = typeof params.q === "string" ? params.q.trim() : "";
  const validPage = typeof params.page === "undefined" ||
    /^(0|[1-9][0-9]{0,3})$/.test(params.page);
  const page = validPage ? Number(params.page ?? "0") : -1;
  const tables = await tableRegistry(page, q);
  if (!tables.ok && tables.status === 401) redirect("/staff/login?status=expired");
  if (!tables.ok) return <main className="staff-shell">
    <h1>Studio QR belum tersedia</h1>
    <p role="alert">{tables.status === 400
      ? "Nomor halaman atau pencarian tidak valid."
      : "Backend gagal mengonfirmasi inventaris meja. Coba lagi setelah koneksi pulih."}</p>
    <Link href="/staff/tables/registry">Kembali ke inventaris</Link>
  </main>;

  // The server returns only verified public aliases; no join proofs are present.
  const printable = tables.data.items;
  const pageUrl = (n: number) => "/staff/tables/registry/print?" +
    new URLSearchParams({ page: String(n), ...(q ? { q } : {}) }).toString();

  return <main className="staff-shell staff-wide scs-qr-print">
    <header className="staff-header"><div>
      <div className="staff-eyebrow">SMART CAFE / TABLE SIGNAGE</div>
      <h1>Cetak QR meja</h1>
      <p className="staff-muted">Buat QR berkualitas vektor untuk meja fisik, tanpa mengirim
        alamat kafe ke penyedia QR eksternal.</p>
    </div><Link className="staff-link-button" href="/staff/tables/registry">
      Kembali ke inventaris
    </Link></header>
    <nav className="staff-breadcrumb"><Link href="/staff">Ruang staf</Link>
      <span>/</span><Link href="/staff/tables">Meja</Link>
      <span>/</span><Link href="/staff/tables/registry">Inventaris</Link>
      <span>/</span><strong>Cetak QR</strong></nav>
    <p className="staff-notice" role="status">Menampilkan halaman inventaris
      {tables.data.page + 1}: {printable.length} dari {tables.data.total} meja yang cocok.
      Navigasi halaman untuk mengambil batch meja lainnya dari database.</p>
    <form method="GET" className="staff-catalog-search scs-qr-studio-search">
      <label htmlFor="qr-print-search">Cari kode meja atau alias</label>
      <div><input type="search" id="qr-print-search" name="q" maxLength={60}
        pattern="[A-Za-z0-9_-]*" defaultValue={q.slice(0, 60)}
        placeholder="Contoh: VIP-03"/>
        <button className="staff-secondary" type="submit">Cari meja</button>
        {q && <Link href="/staff/tables/registry/print">Reset</Link>}
      </div>
    </form>
    <QrPrintStudio key={page + ':' + q} tables={printable.map((table) => ({
      tableId: table.tableId, tableCode: table.tableCode, publicAlias: table.publicAlias,
    }))}/>
    <nav className="staff-breadcrumb" aria-label="Halaman batch QR">
      {tables.data.page > 0 && <Link className="staff-link-button"
        href={pageUrl(tables.data.page - 1)}>Batch sebelumnya</Link>}
      <span>Batch database {tables.data.page + 1}</span>
      {tables.data.hasNext && tables.data.page < 1000 &&
        <Link className="staff-link-button" href={pageUrl(tables.data.page + 1)}>
          Batch berikutnya
        </Link>}
    </nav>
  </main>;
}
