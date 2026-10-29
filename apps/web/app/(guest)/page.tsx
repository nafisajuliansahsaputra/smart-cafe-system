import { fetchSystemStatus } from "@/lib/server-status";

export const dynamic = "force-dynamic";

export default async function HomePage({ searchParams }: { searchParams: Promise<{ notice?: string }> }) {
  const { notice } = await searchParams;
  const status = await fetchSystemStatus();
  return (
    <main>
      <small>Smart Cafe System / Customer &amp; Staff</small>
      <h1>Pesan dari meja lewat QR atau NFC</h1>
      <p>
        Scan kode meja untuk bergabung dengan kode sementara dari waiter, lalu
        pilih menu dan checkout cash melalui sistem. Operator dapat masuk ke
        dashboard staf yang terpisah.
      </p>
      {notice === "left" && <p role="status">Sesi tamu sudah dicabut dari server dan browser.</p>}
      {notice === "revocation-unconfirmed" && <p role="alert">Cookie di browser sudah dihapus, tetapi server belum mengonfirmasi pencabutan token. Hubungi staf bila perangkat tidak aman.</p>}
      <section aria-live="polite">
        <h2>Koneksi layanan</h2>
        {status ? (
          <p>
            API dan database terhubung. Tabel terkonfigurasi:{" "}
            {status.configuredTables}.
          </p>
        ) : (
          <p>
            API atau database belum tersedia. Tidak ada fitur pemesanan aktif.
          </p>
        )}
        <small>Pemesanan hanya tersedia untuk meja aktif melalui QR/NFC. Jangan gunakan uang nyata pada lingkungan demo.</small>
      </section>
    </main>
  );
}
