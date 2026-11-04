import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import {
  getOrderReceipt,
  guestIdentity,
  idr,
  timeWib,
  UUID_PATTERN,
} from "@/lib/guest-server";
import { PrintButton } from "./print-button";
import styles from "./receipt.module.css";

export const dynamic = "force-dynamic";

export default async function PrintableReceiptPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const identity = await guestIdentity();
  if (identity.kind === "signed-out") redirect("/visit");
  if (identity.kind === "unavailable") {
    return (
      <main className="guest-shell">
        <h1>Struk belum dapat diperiksa</h1>
        <p>Verifikasi sesi sedang tidak tersedia. Tidak ada status pembayaran yang diasumsikan.</p>
        <Link href="/visit/orders">Kembali ke pesanan</Link>
      </main>
    );
  }

  const { id } = await params;
  if (!UUID_PATTERN.test(id)) notFound();
  const receipt = await getOrderReceipt(id);
  if (!receipt.ok) {
    if (receipt.status === 401) redirect("/visit");
    if (receipt.status === 403 || receipt.status === 404) notFound();
    return (
      <main className="guest-shell">
        <h1>Struk belum tersedia</h1>
        <p>Data struk tidak dapat diverifikasi. Silakan coba kembali setelah koneksi pulih.</p>
        <Link href={"/visit/orders/" + id}>Kembali ke pesanan</Link>
      </main>
    );
  }

  const data = receipt.data;
  return (
    <main className={"guest-shell " + styles.shell}>
      <nav className={styles.toolbar} aria-label="Tindakan struk">
        <Link className="guest-secondary" href={"/visit/orders/" + id}>
          Kembali ke pesanan
        </Link>
        <PrintButton />
      </nav>
      <article className={styles.paper} aria-label="Struk pembayaran terverifikasi">
        <p className="guest-eyebrow">SMART CAFE SYSTEM</p>
        <h1>Struk pembayaran</h1>
        <p className={styles.subtitle}>Struk diterbitkan hanya setelah pembayaran dikonfirmasi.</p>
        <dl className={styles.meta}>
          <div><dt>Nomor struk</dt><dd>#{data.number}</dd></div>
          <div><dt>Waktu terbit (WIB)</dt><dd>{timeWib(data.issuedAt)}</dd></div>
          <div><dt>Nomor pesanan</dt><dd className={styles.orderId}>{data.orderId}</dd></div>
          <div><dt>Status</dt><dd>LUNAS</dd></div>
        </dl>
        <div className={styles.tableWrap}>
          <table className={styles.lines}>
            <caption>Rincian pembelian</caption>
            <thead>
              <tr>
                <th scope="col">Item</th>
                <th scope="col">Jumlah</th>
                <th scope="col">Harga satuan</th>
                <th scope="col">Total</th>
              </tr>
            </thead>
            <tbody>
              {data.lines.map((line, index) => (
                <tr key={index}>
                  <td>
                    <strong>{line.name}</strong>
                    {line.variantLabel && <small>{line.variantLabel}</small>}
                  </td>
                  <td>{line.quantity}</td>
                  <td>{idr(line.unitPriceIdr)}</td>
                  <td>{idr(line.lineTotalIdr)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className={styles.total}>
          <span>Total dibayar ({data.currency})</span>
          <strong>{idr(data.totalIdr)}</strong>
        </div>
        <p className={styles.note}>
          Nomor pesanan dan jumlah berasal dari struk yang diverifikasi server.
          Halaman ini khusus sesi tamu aktif. Tidak ada nomor telepon atau token sesi dalam dokumen.
        </p>
      </article>
    </main>
  );
}
