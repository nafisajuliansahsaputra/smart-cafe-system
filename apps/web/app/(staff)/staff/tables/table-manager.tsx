"use client";

import { useActionState } from "react";
import Link from "next/link";
import { commandTable } from "./actions";
import type { StaffTable } from "@/lib/staff-server";
import { initialTableAction } from "@/lib/table-action-types";

export function TableManager({ tables }: { tables: StaffTable[] }) {
  const [result, action, pending] = useActionState(commandTable, initialTableAction);
  return (
    <>
      {result.kind !== "idle" && (
        <section className="staff-panel staff-table-feedback" role="status" aria-live="polite">
          <h2>{result.kind === "proof" ? "Kode bergabung baru" : "Hasil tindakan"}</h2>
          <p>{result.message}</p>
          {result.kind === "proof" && result.joinProof && (
            <div className="staff-secret-disclosure">
              <label htmlFor="one-time-proof">Salin kode ini untuk tamu, bukan untuk QR publik</label>
              <input
                id="one-time-proof" readOnly value={result.joinProof}
                autoComplete="off" aria-label="Kode bergabung sementara"
              />
              <small>Kunjungan {result.visitId}. Berlaku sampai {result.expiresAt
                ? new Intl.DateTimeFormat("id-ID", {
                    dateStyle: "medium", timeStyle: "short", timeZone: "Asia/Jakarta",
                  }).format(new Date(result.expiresAt))
                : "waktu kedaluwarsa tidak tersedia"} WIB.</small>
              <p className="staff-muted">Kode hanya tampil pada hasil tindakan ini. Jangan bagikan ke grup publik, URL, log, atau screenshot.</p>
            </div>
          )}
        </section>
      )}
      {tables.length === 0 ? (
        <section className="staff-panel"><h2>Belum ada meja terdaftar</h2>
          <p>Administrator perlu menambahkan meja lewat konfigurasi sebelum layanan dibuka.</p></section>
      ) : (
        <div className="staff-task-grid">
          {tables.map((table) => {
            const manageable = table.manageableVisitId !== null;
            const canOpen = table.tableStatus === "AVAILABLE" && !table.activeVisit;
            return (
              <article className="staff-panel staff-task-card" key={table.tableId}>
                <div className="staff-task-head">
                  <h2>Meja {table.tableCode}</h2>
                  <strong>{table.tableStatus === "OUT_OF_SERVICE"
                    ? "NONAKTIF" : table.activeVisit ? "TERISI" : "TERSEDIA"}</strong>
                </div>
                <p className="staff-muted">Kapasitas {table.capacity} orang</p>
                <p className="staff-muted">Kode QR/NFC: <Link href={`/t/${table.publicAlias}`} target="_blank" rel="noopener noreferrer">Lihat halaman meja</Link></p>
                {canOpen && (
                  <form action={action} className="staff-form">
                    <input type="hidden" name="operation" value="open" />
                    <input type="hidden" name="tableId" value={table.tableId} />
                    <label className="staff-check">
                      <input type="checkbox" name="confirm" value="yes" required />
                      Saya sudah memeriksa meja dan tamu yang datang
                    </label>
                    <button className="staff-primary" type="submit" disabled={pending}>
                      Buka kunjungan
                    </button>
                  </form>
                )}
                {table.activeVisit && manageable && (
                  <div className="staff-table-actions">
                    <p className="staff-muted">Kunjungan bisa dikelola akun ini. Kode lama tidak dapat diambil ulang.</p>
                    {table.proofExpiresAt && (
                      <p className="staff-muted">Kedaluwarsa kode terakhir: {new Intl.DateTimeFormat("id-ID", {
                        dateStyle: "medium", timeStyle: "short", timeZone: "Asia/Jakarta",
                      }).format(new Date(table.proofExpiresAt))} WIB</p>
                    )}
                    <form action={action} className="staff-form">
                      <input type="hidden" name="operation" value="rotate" />
                      <input type="hidden" name="visitId" value={table.manageableVisitId ?? ""} />
                      <label className="staff-check"><input type="checkbox" name="confirm" value="yes" required />
                        Ganti kode lama dan batalkan akses bergabung dari kode sebelumnya
                      </label>
                      <button className="staff-secondary" type="submit" disabled={pending}>Buat kode baru</button>
                    </form>
                    <form action={action} className="staff-form">
                      <input type="hidden" name="operation" value="close" />
                      <input type="hidden" name="visitId" value={table.manageableVisitId ?? ""} />
                      <label className="staff-check"><input type="checkbox" name="confirm" value="yes" required />
                        Semua pesanan dan pembayaran telah selesai. Tutup kunjungan ini.
                      </label>
                      <button className="staff-danger" type="submit" disabled={pending}>Tutup kunjungan</button>
                    </form>
                  </div>
                )}
                {table.activeVisit && !manageable && (
                  <p className="staff-muted">Meja sedang digunakan oleh staf lain. Hubungi supervisor untuk penanganan.</p>
                )}
                {table.tableStatus === "OUT_OF_SERVICE" && !table.activeVisit && (
                  <p className="staff-muted">Meja tidak dapat dibuka karena berstatus nonaktif.</p>
                )}
              </article>
            );
          })}
        </div>
      )}
    </>
  );
}
