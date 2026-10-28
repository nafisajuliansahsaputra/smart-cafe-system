type Props = { params: Promise<{ tableAlias: string }> };

export default async function TableEntry({ params }: Props) {
  const { tableAlias } = await params;
  return (
    <main>
      <small>Smart Cafe System / QR or NFC</small>
      <h1>Meja: {tableAlias}</h1>
      <section>
        <h2>Belum bisa bergabung</h2>
        <p>
          Aktivasi meja dan bukti kunjungan dari staf belum tersedia pada M1.
          Kode QR saja tidak memberi izin membuat pesanan.
        </p>
      </section>
    </main>
  );
}
