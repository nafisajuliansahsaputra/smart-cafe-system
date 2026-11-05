import Link from "next/link";
import "../showcase.css";
import { fetchSystemStatus } from "@/lib/server-status";

export const dynamic = "force-dynamic";

const capabilities = [
  { index: "01", title: "Customer ordering", detail: "Scan QR meja, masuk tanpa akun, pilih menu dan pantau pesanan." },
  { index: "02", title: "Order Control Center", detail: "Kasir mengelola antrean, keputusan cash, dan status pembayaran." },
  { index: "03", title: "Kitchen & waiter", detail: "Dapur menyiapkan tiket. Waiter mengantar dan menerima pembayaran cash." },
  { index: "04", title: "Games & reporting", detail: "Peminjaman permainan fisik, kontrol unit, shift kas, dan laporan." },
];

export default async function HomePage({
  searchParams,
}: {
  searchParams: Promise<{ notice?: string }>;
}) {
  const { notice } = await searchParams;
  const api = await fetchSystemStatus();
  return (
    <main className="portfolio-home">
      <div className="home-frame">
        <header className="home-nav" aria-label="Navigasi utama">
          <Link href="/" className="home-brand" aria-label="Smart Cafe System, beranda">
            <span className="home-brand-symbol" aria-hidden="true"><span /></span>
            <span>SMART<span className="home-brand-light">CAFE</span><span className="home-brand-dot">.</span></span>
          </Link>
          <nav aria-label="Menu utama" className="home-nav-links">
            <a href="#workflow">Cara kerja</a>
            <a href="#features">Fitur</a>
            <Link href="/staff/login" className="home-nav-staff">Portal staf <span aria-hidden="true">↗</span></Link>
          </nav>
        </header>

        {notice === "left" && (
          <p className="home-message" role="status">Sesi tamu berhasil diakhiri.</p>
        )}
        {notice === "revocation-unconfirmed" && (
          <p className="home-message home-message-warning" role="alert">
            Sesi browser dihapus, tetapi pencabutan server belum terkonfirmasi. Hubungi staf jika perangkat tidak aman.
          </p>
        )}

        <section className="home-hero" aria-labelledby="home-heading">
          <div className="home-hero-copy">
            <div className="home-eyebrow"><span className="home-live-mark" aria-hidden="true" /> ONE SYSTEM. EVERY TABLE.</div>
            <h1 id="home-heading">Good coffee.<br /><em>Better flow.</em></h1>
            <p className="home-hero-subtitle">
              Dari scan meja sampai pesanan tiba. Semua orang di kafe terhubung
              dalam satu alur yang simpel, cepat, dan lebih terorganisir.
            </p>
            <div className="home-actions">
              <Link href="/showcase" className="home-button home-button-primary">Jelajahi demo <span aria-hidden="true">↗</span></Link>
              <a href="#workflow" className="home-button home-button-outline">Lihat cara kerja <span aria-hidden="true">↓</span></a>
            </div>
            <div className="home-hero-foot"><span className="home-foot-icon" aria-hidden="true">✳</span><span>{api ? `Backend terhubung / ${api.configuredTables} meja terdaftar` : "Showcase online / API review sedang diperiksa"}<br />Preview menggunakan data sintetis. Pembayaran nyata dinonaktifkan.</span></div>
          </div>

          <div className="home-stage" aria-label="Ilustrasi antarmuka pemesanan dan operasional kafe">
            <span className="home-stage-orbit home-orbit-one" aria-hidden="true" />
            <span className="home-stage-orbit home-orbit-two" aria-hidden="true" />
            <div className="home-stage-note" aria-hidden="true">ORDER<br />SMARTER<br /><span>↘</span></div>
            <div className="home-preview-phone">
              <div className="home-phone-camera" aria-hidden="true" />
              <div className="home-phone-top"><span>9:41</span><span>●●●</span></div>
              <div className="home-phone-heading"><span className="home-tiny-logo">S<span>.</span></span><span>TABLE 04 <span aria-hidden="true">↗</span></span></div>
              <div className="home-preview-eyebrow">YOUR TABLE. YOUR ORDER.</div>
              <div className="home-phone-title">Pick your<br /><i>happy place.</i></div>
              <div className="home-menu-card">
                <div className="home-menu-visual" aria-hidden="true"><span className="home-cup-plate"><span className="home-cup-foam" /></span></div>
                <div className="home-menu-caption"><strong>Caramel Oat Latte</strong><span>Cold & creamy</span><b>Rp 32.000</b></div>
              </div>
              <div className="home-menu-bottom"><span>YOUR ORDER <b>02 ITEMS</b></span><span aria-hidden="true">↗</span></div>
            </div>
            <div className="home-status-card">
              <span className="home-status-icon" aria-hidden="true">✓</span>
              <span><small>ORDER #024</small><strong>Ready to serve</strong><span>Kitchen → Waiter</span></span>
            </div>
            <div className="home-stage-index">01 / CONNECTED EXPERIENCE</div>
          </div>
        </section>
      </div>

      <div className="home-ticker" aria-label="Fitur sistem">
        <div>SCAN TO ORDER <span>✳</span> ZERO ACCOUNT NEEDED <span>✳</span> KITCHEN IN SYNC <span>✳</span> ALL IN ONE FLOW <span>✳</span> SCAN TO ORDER <span>✳</span></div>
      </div>

      <section id="workflow" className="home-section home-process" aria-labelledby="workflow-heading">
        <div className="home-section-title"><span className="home-section-index">01 / THE FLOW</span><h2 id="workflow-heading">From hello to <em>here you go.</em></h2><p>Satu sistem, banyak peran. Pesanan bergerak tanpa harus berpindah aplikasi.</p></div>
        <div className="home-steps">
          <article><span className="home-step-num">01 <span>↗</span></span><div className="home-step-drawing home-step-qr" aria-hidden="true"><span>▦</span></div><h3>Scan & join</h3><p>Pelanggan scan QR/NFC, dapat akses meja aktif, tanpa perlu membuat akun.</p></article>
          <article><span className="home-step-num">02 <span>↗</span></span><div className="home-step-drawing home-step-menu" aria-hidden="true"><span>≋</span></div><h3>Order & confirm</h3><p>Pilih menu, checkout, lalu kasir memvalidasi pesanan dan metode pembayaran.</p></article>
          <article><span className="home-step-num">03 <span>↗</span></span><div className="home-step-drawing home-step-kitchen" aria-hidden="true"><span>◉</span></div><h3>Make & deliver</h3><p>Dapur menyiapkan, waiter mengantar, dan pelanggan memantau status.</p></article>
        </div>
      </section>

      <section id="features" className="home-section home-features" aria-labelledby="features-heading">
        <div className="home-features-top"><span className="home-section-index">02 / BUILT FOR REAL OPERATIONS</span><h2 id="features-heading">Not just a menu.<br /><em>The whole café.</em></h2></div>
        <div className="home-feature-grid">
          {capabilities.map((item) => (
            <article className="home-feature" key={item.index}>
              <span>{item.index} / SYSTEM</span><h3>{item.title}</h3><p>{item.detail}</p><span className="home-feature-arrow" aria-hidden="true">↗</span>
            </article>
          ))}
        </div>
      </section>

      <section className="home-outro" aria-labelledby="home-outro-heading">
        <div><span className="home-section-index">03 / TRY THE EXPERIENCE</span><h2 id="home-outro-heading">Less waiting.<br /><em>More living.</em></h2></div>
        <div className="home-outro-actions"><p>Jelajahi alur pelanggan, kasir, dapur, dan waiter di sandbox presentasi yang tidak menyimpan data pribadi atau memproses uang.</p><Link href="/showcase" className="home-button home-button-light">Buka demo interaktif <span aria-hidden="true">↗</span></Link></div>
      </section>

      <footer className="home-footer"><span>© SMART CAFE SYSTEM / NATSX</span><span>SHOWCASE MODE · NOT A LIVE MERCHANT SERVICE</span><Link href="/staff/login">Staff portal ↗</Link></footer>
    </main>
  );
}
