import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import { staffIdentity, staffTables } from "@/lib/staff-server";
import { QrPrintStudio } from "./qr-studio";

export const dynamic = "force-dynamic";

const publicAlias = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;
const tableCode = /^[A-Z0-9][A-Z0-9_-]{0,39}$/;

export default async function PrintableTableQrs() {
  const identity = await staffIdentity();
  if (identity.kind === "signed-out") redirect("/staff/login?status=expired");
  if (identity.kind === "unavailable") return <main className="staff-shell">
    <h1>Studio QR belum tersedia</h1>
    <p role="alert">Identitas staf tidak dapat diverifikasi. Tidak ada QR yang diterbitkan.</p>
    <Link href="/staff/tables/registry">Kembali ke inventaris</Link>
  </main>;
  if (!identity.staff.roles.some(role => role === "MANAGER" || role === "ADMIN")) notFound();

  const tables = await staffTables();
  if (!tables.ok && tables.status === 401) redirect("/staff/login?status=expired");
  if (!tables.ok) return <main className="staff-shell">
    <h1>Studio QR belum tersedia</h1>
    <p role="alert">Backend gagal mengonfirmasi inventaris meja. Coba lagi setelah koneksi pulih.</p>
    <Link href="/staff/tables/registry">Kembali ke inventaris</Link>
  </main>;

  // Table URLs are permanent public identifiers, never visit proofs or bearer tokens.
  // Reject untrusted/malformed aliases rather than encode arbitrary backend strings.
  const printable = tables.data.filter((table) =>
    tableCode.test(table.tableCode) && table.publicAlias.length <= 100 &&
    publicAlias.test(table.publicAlias),
  );

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
    {printable.length !== tables.data.length && <p className="staff-notice" role="alert">
      {tables.data.length - printable.length} meja dilewati karena kode atau alias tidak valid.
      Periksa inventaris sebelum mencetak.</p>}
    <QrPrintStudio tables={printable.map((table) => ({
      tableId: table.tableId, tableCode: table.tableCode, publicAlias: table.publicAlias,
    }))}/>
  </main>;
}
