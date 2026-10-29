"use server";

import { revalidatePath } from "next/cache";
import {
  canUseWaiter, dataFromEnvelope, staffApi, staffIdentity, staffToken,
} from "@/lib/staff-server";
import type { TableActionState } from "@/lib/table-action-types";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function error(message: string): TableActionState {
  return { kind: "error", message };
}
function apiError(status: number): TableActionState {
  if (status === 401) return error("Sesi staf habis. Masuk kembali sebelum melakukan tindakan.");
  if (status === 403) return error("Akses ditolak. Hanya penanggung jawab meja atau supervisor yang boleh mengubah kunjungan.");
  if (status === 404) return error("Meja atau kunjungan tidak ditemukan. Segarkan daftar.");
  if (status === 409) return error("Status kunjungan berubah atau masih ada pesanan/pembayaran belum selesai. Segarkan daftar.");
  if (status === 0 || status >= 500) return error("Server tidak dapat mengonfirmasi tindakan. Segarkan sebelum mencoba lagi.");
  return error("Perintah tidak dapat diproses. Periksa status meja dan coba lagi.");
}

export async function commandTable(
  _previous: TableActionState, form: FormData,
): Promise<TableActionState> {
  const operation = form.get("operation");
  const tableId = form.get("tableId");
  const visitId = form.get("visitId");

  if (!["open", "rotate", "close"].includes(String(operation)) ||
      form.get("confirm") !== "yes") {
    return error("Konfirmasi tindakan wajib diisi.");
  }
  if (operation === "open") {
    if (typeof tableId !== "string" || !UUID.test(tableId)) return error("ID meja tidak valid.");
  } else if (typeof visitId !== "string" || !UUID.test(visitId)) {
    return error("ID kunjungan tidak valid. Segarkan daftar.");
  }
  const current = await staffIdentity();
  if (current.kind !== "signed-in") {
    return error("Sesi staf belum dapat diverifikasi. Masuk kembali atau periksa koneksi.");
  }
  if (!canUseWaiter(current.staff.roles)) return error("Peran staf tidak diizinkan mengelola meja.");

  const token = await staffToken();
  if (!token) return error("Sesi staf berakhir. Masuk kembali.");
  const path = operation === "open"
    ? "/api/v1/waiter/table-visits"
    : `/api/v1/waiter/table-visits/${visitId}/${operation === "rotate" ? "join-proof" : "close"}`;
  const result = await staffApi(
    path, "POST", token, operation === "open" ? { tableId } : undefined,
  );
  if (result.status !== 200) return apiError(result.status);

  const payload = dataFromEnvelope(result.payload);
  if (operation === "close") {
    if (payload !== "CLOSED") return error(
      "Server merespons tanpa status penutupan yang valid. Segarkan daftar sebelum melanjutkan.",
    );
    revalidatePath("/staff/tables");
    return { kind: "success", message: "Kunjungan meja berhasil ditutup. Sesi tamu lama sudah dicabut oleh backend." };
  }
  if (
    typeof payload !== "object" || payload === null ||
    !("joinProof" in payload) || typeof payload.joinProof !== "string" ||
    !/^[A-Za-z0-9_-]{43}$/.test(payload.joinProof) ||
    !("id" in payload) || typeof payload.id !== "string" || !UUID.test(payload.id) ||
    !("tableId" in payload) || typeof payload.tableId !== "string" || !UUID.test(payload.tableId) ||
    (operation === "open" && payload.tableId !== tableId) ||
    (operation === "rotate" && payload.id !== visitId) ||
    !("proofExpiresAt" in payload) || typeof payload.proofExpiresAt !== "string" ||
    Number.isNaN(Date.parse(payload.proofExpiresAt))
  ) {
    return error("Server berhasil merespons tetapi kode tidak dapat diverifikasi. Segarkan status, lalu rotasi kode bila perlu.");
  }
  revalidatePath("/staff/tables");
  return {
    kind: "proof",
    message: operation === "open"
      ? "Kunjungan aktif. Berikan kode di bawah langsung kepada tamu di meja."
      : "Kode baru berhasil dibuat. Kode sebelumnya langsung tidak berlaku.",
    joinProof: payload.joinProof,
    visitId: payload.id,
    expiresAt: payload.proofExpiresAt,
  };
}
