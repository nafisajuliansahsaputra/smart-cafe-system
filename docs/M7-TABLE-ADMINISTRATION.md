# M7: Pengaturan master meja operasional

Ini adalah fitur backend dan staff dashboard **nyata**, bukan `/showcase` portfolio.

## Fungsi

- Manager dan admin dapat membuka `/staff/table-settings` dari ruang operasional.
- Tambah meja fisik dengan kode dan alias QR/NFC unik, kapasitas 1 sampai 100.
- Edit kapasitas dan ubah status antara `AVAILABLE` dan `OUT_OF_SERVICE`.
- Endpoint terautentikasi: `GET/POST /api/v1/admin/tables` dan `PATCH /api/v1/admin/tables/{id}`.
- Tidak ada endpoint hapus atau ubah kode/alias setelah dibuat, agar URL QR/NFC yang sudah dicetak tidak berubah dan referensi sejarah transaksi tetap terjaga.

## Proteksi transaksi dan data

- Semua operasi menggunakan staf bearer server-side dan kontrol `MANAGER/ADMIN` pada Spring Security, bukan kepercayaan pada kontrol UI.
- Kode/alias baru dibatasi ke karakter URL aman; duplikat ditolak dengan konflik 409.
- Pembaruan melakukan `SELECT ... FOR UPDATE` pada baris meja, sama seperti pembukaan kunjungan. Kunjungan `ACTIVE` atau `CLOSING` memblokir perubahan status maupun kapasitas hingga ditutup. Pembukaan kunjungan tidak mengizinkan `OUT_OF_SERVICE`.
- Audit `TABLE_CREATED` dan `TABLE_SETTINGS_CHANGED` disimpan dalam transaksi database yang sama.
- Next.js memvalidasi payload JSON server dan menolak operasi bila identitas maupun daftar meja tidak bisa dikonfirmasi. Nama/kode lama tetap didukung pada pembacaan tanpa melemahkan pembatasan kode meja baru.

## Verifikasi

Tes Java/Testcontainers membuktikan otorisasi untuk manager, penolakan waiter, konflik duplikat, pembaruan kapasitas/status, penolakan pembukaan meja nonaktif, blokir perubahan ketika kunjungan aktif, input berbahaya dan audit. Browser Playwright menguji pembuatan/pembaruan riil melalui UI manager, viewport sempit 320px, penolakan akses cashier/waiter/kitchen, dan screenshot pengaturan meja.

Tes hanya memakai PostgreSQL dan staf **sintetis** dalam lingkungan sementara. Tidak ada perubahan ke meja, pelanggan atau merchant sungguhan.

## Batasan / berikutnya

- Daftar API ini dibatasi 250 meja; perlu pagination server-side bila jumlah meja bertambah jauh.
- Belum ada generator untuk file cetak QR/NFC, peta posisi meja, pengaturan jadwal, atau perizinan staf yang lebih granular.
- QR/NFC memuat hanya alias meja publik, **bukan** kredensial kunjungan. Pembukaan sesi dan bukti bergabung sementara tetap melalui waiter.
- Pengaturan ini tidak menuntaskan integrasi merchant QRIS/VA asli, pengiriman struk eksternal, atau hosting production.
