# M7: Jejak aktivitas operasional manager dan admin

Ini adalah fitur aplikasi Smart Cafe System **nyata**, bukan simulasi `/showcase`.

## Fungsi

- `/staff/audit`: halaman histori audit untuk MANAGER/ADMIN.
- `GET /api/v1/admin/audit-events?page=0&q=...`: API baca-saja yang diproteksi oleh kontrol role Spring Security.
- Cari berdasarkan jenis aktivitas (mis. `CAFE_TABLE_CREATED`), jenis objek, atau username staf.
- Pagination 25 aktivitas per halaman, maksimal halaman 1000. Data diurutkan dari timestamp dan UUID terbaru.
- Waktu ditampilkan dalam WIB, identitas staf menggunakan login otentik, dan objek ditunjukkan dengan jenis + UUID.
- Tidak ada fungsi menghapus atau mengubah audit; data diambil langsung dari `audit_logs` PostgreSQL.

## Keamanan

- API admin hanya menerima bearer staf dengan peran MANAGER/ADMIN; role lain mendapatkan 403 dan route Next.js tidak dibuka.
- Hanya kolom audit yang sudah disaring yang dapat keluar dari backend: ID, operasi, jenis/ID sumber daya, nama operator dan timestamp.
- Tidak mengembalikan `safe_before_after_diff`, `reason`, `correlation_id`, token, payload pembayaran atau data pelanggan.
- Pencarian dibatasi 60 karakter dan hanya huruf/angka/`_`/`-`, diparameterkan melalui JDBC. Karakter underscore di-escape agar tidak berperan sebagai wildcard SQL.
- Respons API divalidasi pada layer Next.js server sebelum dipaparkan. Jika API down atau payload tidak valid, halaman gagal secara aman alih-alih menampilkan data audit fiktif.
- Data raw audit bukan rekaman penuh seluruh aktivitas bisnis; hanya aksi yang benar-benar memanggil audit logger tercatat. Waktu terbit event berbeda dengan peristiwa pada perangkat offline.

## Verifikasi

- JUnit + Testcontainers: login manager, cari 28 event, uji halaman pertama/kedua, penolakan pencarian tidak valid, tidak ada kolom rahasia, akses WAITER/KITCHEN/CASHIER ditolak.
- Playwright: manager membuat meja nyata melalui endpoint PostgreSQL-backed, membuka histori, mencari event `CAFE_TABLE_CREATED`, menguji pencarian kosong, viewport 320 px, dan memverifikasi role lain tidak bisa membuka halaman.
- Screenshot manager yang aman dipublikasikan sebagai artifact CI berumur tujuh hari bersama bukti full-stack lain.

## Batasan yang masih perlu diselesaikan

- Aplikasi memerlukan kebijakan retensi audit, backup/pemulihan audit, indeks khusus pencarian saat volume tumbuh, serta review privasi staf sebelum production.
- Penyimpanan audit tidak boleh dijadikan klaim independen bahwa semua pembayaran sudah direkonsiliasi dengan bank.
- Seluruh UI belum production-ready sebelum persetujuan release, branch protection, staging hosted HTTPS, merchant QRIS/VA sungguhan, dan pengiriman struk yang disetujui.
