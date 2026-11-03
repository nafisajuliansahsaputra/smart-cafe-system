"use client";

import { useState, useSyncExternalStore } from "react";
import { qrcodegen } from "@/lib/vendor/qrcodegen";
import styles from "./qr-studio.module.css";

type PrintableTable = { tableId: string; tableCode: string; publicAlias: string };
type Props = { tables: PrintableTable[] };

const PAGE_SIZE = 8;
const subscribe = () => () => {};
const clientOrigin = () => window.location.origin;
const serverOrigin = () => null;

function qrSvgParts(url: string): { size: number; path: string } {
  // Encoder is bundled locally under its retained MIT license; no URL goes to a QR service.
  const qr = qrcodegen.QrCode.encodeText(url, qrcodegen.QrCode.Ecc.MEDIUM);
  const border = 4; // ISO QR quiet-zone recommendation
  let path = "";
  for (let y = 0; y < qr.size; y++) {
    for (let x = 0; x < qr.size; x++) {
      if (qr.getModule(x, y)) path += `M${x + border},${y + border}h1v1h-1z`;
    }
  }
  return { size: qr.size + border * 2, path };
}

function QrMark({ url }: { url: string }) {
  const { size, path } = qrSvgParts(url);
  return (
    <svg className={styles.qrSymbol} data-qr-url={url} xmlns="http://www.w3.org/2000/svg"
      viewBox={`0 0 ${size} ${size}`} aria-label={`QR menuju ${url}`}
      role="img" shapeRendering="crispEdges">
      <rect width={size} height={size} fill="#fff" />
      <path d={path} fill="#000" />
    </svg>
  );
}

function saveQrSvg(table: PrintableTable, url: string) {
  const { size, path } = qrSvgParts(url);
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 ${size} ${size}" shape-rendering="crispEdges"><rect width="${size}" height="${size}" fill="white"/><path d="${path}" fill="black"/></svg>`;
  const blobUrl = URL.createObjectURL(new Blob([svg], { type: "image/svg+xml;charset=utf-8" }));
  const anchor = document.createElement("a");
  anchor.href = blobUrl;
  anchor.download = `smart-cafe-meja-${table.tableCode.replace(/[^A-Za-z0-9_-]/g, "_").slice(0,40)}.svg`;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  window.setTimeout(() => URL.revokeObjectURL(blobUrl), 1000);
}

export function QrPrintStudio({ tables }: Props) {
  const origin = useSyncExternalStore(subscribe, clientOrigin, serverOrigin);
  const [selection, setSelection] = useState("all");
  const [page, setPage] = useState(0);
  const ordered = [...tables].sort((a, b) => a.tableCode.localeCompare(b.tableCode, "id"));
  const picked = selection === "all" ? ordered :
    ordered.filter((table) => table.tableId === selection);
  const totalPages = Math.max(1, Math.ceil(picked.length / PAGE_SIZE));
  const safePage = Math.min(page, totalPages - 1);
  const visible = picked.slice(safePage * PAGE_SIZE, (safePage + 1) * PAGE_SIZE);
  const isLocal = origin !== null && /^(?:localhost|127\.0\.0\.1|\[::1\])$/.test(
    new URL(origin).hostname,
  );

  return <div className={styles.studio}>
    <div className={styles.controls}>
      <label htmlFor="qr-selection">Meja untuk dicetak</label>
      <select id="qr-selection" value={selection} onChange={(event) => {
        setSelection(event.target.value);
        setPage(0);
      }}>
        <option value="all">Semua meja di batch ini ({tables.length}), per halaman</option>
        {ordered.map((table) => <option key={table.tableId} value={table.tableId}>
          Meja {table.tableCode}
        </option>)}
      </select>
      <div className={styles.toolbar}>
        <button type="button" className="staff-primary"
          onClick={() => window.print()} disabled={!origin || visible.length === 0}>
          Cetak halaman / Simpan PDF
        </button>
        {selection === "all" && totalPages > 1 && <>
          <button className="staff-secondary" type="button" disabled={safePage === 0}
            onClick={() => setPage((value) => Math.max(0, value - 1))}>Sebelumnya</button>
          <span role="status">Halaman {safePage + 1} dari {totalPages}</span>
          <button className="staff-secondary" type="button" disabled={safePage >= totalPages - 1}
            onClick={() => setPage((value) => Math.min(totalPages - 1, value + 1))}>Berikutnya</button>
        </>}
      </div>
      {isLocal && <p role="alert" className={styles.warning}>
        Alamat saat ini adalah localhost. QR hanya bekerja pada perangkat lokal ini.
        Untuk QR fisik, buka studio lewat domain HTTPS kafe yang dapat diakses pelanggan.
      </p>}
      <p className={styles.hint}>
        QR menyimpan alamat meja publik, bukan kode bergabung sementara. Uji dengan kamera
        HP sebelum mencetak. Download SVG berisi QR vektor tanpa ketergantungan layanan eksternal.
      </p>
    </div>
    {!origin ? <p role="status">Menyiapkan alamat QR dari domain yang sedang dibuka…</p> :
      visible.length === 0 ? <p role="status">Belum ada meja yang dapat dicetak.</p> :
      <div className={styles.sheet}>
        {visible.map((table) => {
          const url = `${origin}/t/${table.publicAlias}`;
          return <article key={table.tableId} className={styles.card}>
            <span className={styles.brand}>SMART CAFE SYSTEM</span>
            <h2>Meja {table.tableCode}</h2>
            <QrMark url={url}/>
            <p className={styles.url}>{url}</p>
            <p className={styles.instructions}>Scan untuk melihat menu. Minta kode bergabung
              sementara kepada waiter sebelum memesan.</p>
            <button type="button" className={styles.download}
              onClick={() => saveQrSvg(table, url)}>Unduh QR SVG</button>
          </article>;
        })}
      </div>}
    <p className={styles.printHint}>Hanya halaman yang sedang terlihat yang akan dicetak.
      Untuk meja lainnya, gunakan navigasi halaman di atas.</p>
  </div>;
}
