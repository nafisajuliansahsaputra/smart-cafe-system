import { fetchSystemStatus } from "@/lib/server-status";

export const dynamic = "force-dynamic";

export default async function HomePage() {
  const status = await fetchSystemStatus();
  return (
    <main>
      <small>Smart Cafe System / M1 engineering scaffold</small>
      <h1>Fondasi sistem kafe</h1>
      <p>
        Pemesanan tamu, Order Control Center, dapur, dan waiter akan tersedia
        setelah autentikasi dan alur transaksi terimplementasi pada M2.
      </p>
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
        <small>Halaman ini bukan simulasi pesanan atau pembayaran.</small>
      </section>
    </main>
  );
}
