"use client";

import { useState } from "react";
import Link from "next/link";
import "../demo.css";

type Role = "guest" | "cashier" | "kitchen" | "waiter";

const menu = [
  { name: "Iced Oat Latte", detail: "Espresso, oat milk, ice", price: 32000, code: "01", kind: "coffee" },
  { name: "Butter Croissant", detail: "Flaky, baked fresh", price: 24000, code: "02", kind: "pastry" },
];
const roles: { key: Role; label: string; number: string }[] = [
  { key: "guest", label: "Customer", number: "01" },
  { key: "cashier", label: "Cashier / OCC", number: "02" },
  { key: "kitchen", label: "Kitchen / KDS", number: "03" },
  { key: "waiter", label: "Waiter", number: "04" },
];
const steps = [
  { title: "Buat pesanan", description: "Pelanggan memesan dari meja", role: "guest" as Role },
  { title: "Persetujuan kasir", description: "Cash belum dianggap lunas", role: "cashier" as Role },
  { title: "Persiapan dapur", description: "Pesanan diteruskan ke KDS", role: "kitchen" as Role },
  { title: "Pengantaran", description: "Waiter mengantar makanan", role: "waiter" as Role },
  { title: "Pembayaran cash", description: "Pencatatan setelah uang diterima", role: "waiter" as Role },
  { title: "Struk selesai", description: "Pelanggan menerima struk simulasi", role: "guest" as Role },
];
const rupiah = (value: number) => new Intl.NumberFormat("id-ID", {
  style: "currency", currency: "IDR", maximumFractionDigits: 0,
}).format(value);

export default function PortfolioDemo() {
  const [role, setRole] = useState<Role>("guest");
  const [stage, setStage] = useState(0);
  const [counts, setCounts] = useState([1, 0]);
  const total = counts.reduce((sum, count, index) => sum + count * menu[index].price, 0);
  const count = counts.reduce((sum, quantity) => sum + quantity, 0);
  const finished = stage === 5;

  function changeQuantity(index: number, delta: number) {
    setCounts((previous) => previous.map((quantity, position) =>
      position === index ? Math.max(0, Math.min(9, quantity + delta)) : quantity));
  }
  function advance() {
    const next = Math.min(5, stage + 1);
    setStage(next);
    setRole(steps[next].role);
  }
  function reset() {
    setStage(0);
    setRole("guest");
    setCounts([1, 0]);
  }
  return (
    <main className="demo-shell">
      <header className="demo-topbar">
        <Link href="/" className="demo-wordmark" aria-label="Kembali ke Smart Cafe System">
          <span className="demo-wordmark-mark" aria-hidden="true">✳</span> SMARTCAFE<span>.</span>
        </Link>
        <div className="demo-topbar-right"><span className="demo-pill">SANDBOX PRESENTASI</span><Link href="/">Kembali ke beranda <span aria-hidden="true">↗</span></Link></div>
      </header>
      <div className="demo-layout">
        <aside className="demo-sidebar">
          <div className="demo-sidebar-head">
            <span className="demo-eyebrow">INTERACTIVE PRODUCT TOUR / 2026</span>
            <h1>One café.<br /><em>One flow.</em></h1>
            <p>Ganti peran dan jalankan simulasi dari meja pelanggan sampai struk selesai.</p>
          </div>
          <p className="demo-label">01 / PILIH SUDUT PANDANG</p>
          <nav aria-label="Peran demo" className="demo-role-tabs">
            {roles.map((entry) => (
              <button type="button" key={entry.key} aria-pressed={entry.key === role}
                className={entry.key === role ? "demo-role active" : "demo-role"}
                onClick={() => setRole(entry.key)}>
                <span>{entry.number}</span><strong>{entry.label}</strong><span aria-hidden="true">↗</span>
              </button>
            ))}
          </nav>
          <p className="demo-label">02 / ORDER JOURNEY</p>
          <ol className="demo-timeline">
            {steps.map((entry, index) => (
              <li key={entry.title} className={index < stage ? "done" : index === stage ? "current" : ""}>
                <span className="demo-step-marker" aria-hidden="true">{index < stage ? "✓" : index + 1}</span>
                <span><strong>{entry.title}</strong><small>{entry.description}</small></span>
              </li>
            ))}
          </ol>
          <button type="button" className="demo-reset" onClick={reset}>↻ &nbsp; Mulai ulang simulasi</button>
        </aside>

        <div className="demo-workspace">
          <div className="demo-safety" role="note"><span aria-hidden="true">ⓘ</span><span>Demo ini hanya simulasi di browser. Tidak terhubung ke API transaksi, tidak menerima pembayaran, dan tidak menyimpan data pelanggan.</span></div>
          <header className="demo-workspace-header">
            <div><span className="demo-eyebrow">{role === "guest" ? "CUSTOMER EXPERIENCE" : role === "cashier" ? "ORDER CONTROL CENTER" : role === "kitchen" ? "KITCHEN DISPLAY SYSTEM" : "WAITER WORKSPACE"}</span>
              <h2>{role === "guest" ? "Your table, your call." : role === "cashier" ? "Orders, under control." : role === "kitchen" ? "Fresh from the kitchen." : "Great service, on time."}</h2>
            </div>
            <div className="demo-table-tag"><small>TABLE</small><b>04</b></div>
          </header>
          <div className="demo-workspace-grid">
            <section className="demo-panel demo-main-panel" aria-labelledby="demo-main-heading">
              {role === "guest" && stage === 0 ? (
                <>
                  <div className="demo-panel-heading"><span className="demo-eyebrow">A LITTLE SOMETHING GOOD</span><h3 id="demo-main-heading">What sounds good?</h3><p>Pilih menu untuk pesanan contoh. Harga bersifat fiktif.</p></div>
                  <div className="demo-menu-list">{menu.map((item, index) => (
                    <div className="demo-menu-item" key={item.code}>
                      <div className={"demo-food-art " + item.kind} aria-hidden="true"><span>{item.kind === "coffee" ? "☕" : "✦"}</span></div>
                      <div className="demo-menu-description"><small>FEATURED / {item.code}</small><h4>{item.name}</h4><p>{item.detail}</p><b>{rupiah(item.price)}</b></div>
                      <div className="demo-quantity" aria-label={"Jumlah " + item.name}>
                        <button type="button" aria-label={"Kurangi " + item.name} disabled={counts[index] === 0} onClick={() => changeQuantity(index, -1)}>−</button>
                        <output aria-label={"Kuantitas " + item.name}>{counts[index]}</output>
                        <button type="button" aria-label={"Tambah " + item.name} disabled={counts[index] === 9} onClick={() => changeQuantity(index, 1)}>+</button>
                      </div>
                    </div>
                  ))}</div>
                  <button type="button" className="demo-next-button" disabled={count === 0} onClick={advance}>Kirim pesanan cash <span aria-hidden="true">↗</span></button>
                </>
              ) : role === "guest" && finished ? (
                <>
                  <div className="demo-panel-heading"><span className="demo-eyebrow">ORDER COMPLETED</span><h3 id="demo-main-heading">Thanks for stopping by.</h3><p>Pembayaran cash disimulasikan sudah diterima. Ini hanya struk contoh.</p></div>
                  <div className="demo-receipt"><span>SMARTCAFE. / DEMO RECEIPT</span><strong>ORDER #DEMO-024</strong><div>TABLE 04 · CASH (SIMULATED)</div>{menu.map((item, index) => counts[index] > 0 && <p key={item.code}><span>{counts[index]}× {item.name}</span><b>{rupiah(counts[index] * item.price)}</b></p>)}<p className="demo-receipt-total"><strong>TOTAL</strong><strong>{rupiah(total)}</strong></p><small>NOT A FISCAL RECEIPT · NO REAL PAYMENT</small></div>
                  <button type="button" className="demo-next-button" onClick={reset}>Coba simulasi lagi <span aria-hidden="true">↻</span></button>
                </>
              ) : role === "guest" ? (
                <div className="demo-waiting"><span className="demo-eyebrow">ORDER #DEMO-024</span><div className="demo-wait-icon" aria-hidden="true">↗</div><h3 id="demo-main-heading">Your order is on its way.</h3><p>{steps[stage].description}. Beralih ke peran <strong>{roles.find((item) => item.key === steps[stage].role)?.label}</strong> untuk melanjutkan.</p><span className="demo-wait-tag">{steps[stage].title.toUpperCase()}</span></div>
              ) : (
                <>
                  <div className="demo-panel-heading"><span className="demo-eyebrow">LIVE OPERATIONS / EXAMPLE DATA</span><h3 id="demo-main-heading">{role === "cashier" ? "Order queue" : role === "kitchen" ? "Production tickets" : "Service tasks"}</h3><p>Satu pesanan contoh mengikuti status yang sama di seluruh peran.</p></div>
                  <div className="demo-ops-card">
                    <div className="demo-ops-top"><span>ORDER #DEMO-024</span><span className="demo-state-dot">{steps[stage].title}</span></div>
                    <h4>Table 04 <span>· Cash</span></h4>
                    <div className="demo-ops-lines">{menu.map((item, index) => counts[index] > 0 && <div key={item.code}><span>{counts[index]}× {item.name}</span><strong>{rupiah(counts[index] * item.price)}</strong></div>)}</div>
                    <div className="demo-ops-footer"><span>ORDER TOTAL</span><strong>{rupiah(total)}</strong></div>
                    <p className="demo-payment-note">{finished ? "Simulasi cash tercatat." : "Cash belum dianggap lunas sebelum waiter mencatat penerimaan uang."}</p>
                  </div>
                  {role === steps[stage].role && stage < 5 ? (
                    <button type="button" className="demo-next-button" onClick={advance}>{stage === 1 ? "Setujui untuk dapur" : stage === 2 ? "Tandai siap diantar" : stage === 3 ? "Tandai sudah diantar" : "Catat penerimaan cash"} <span aria-hidden="true">↗</span></button>
                  ) : (
                    <div className="demo-wait-inline" role="status">Tahap ini ditangani oleh <strong>{roles.find((item) => item.key === steps[stage].role)?.label}</strong>. Pilih peran tersebut untuk melanjutkan.</div>
                  )}
                </>
              )}
            </section>
            <aside className="demo-detail-column">
              <section className="demo-summary-card">
                <span className="demo-eyebrow">ORDER PROGRESS</span>
                <div className="demo-progress-number">{stage + 1}<span>/06</span></div>
                <div className="demo-progress-rail" aria-label={"Progress simulasi " + Math.round((stage + 1) / 6 * 100) + " persen"}><span style={{ width: ((stage + 1) / 6 * 100) + "%" }} /></div>
                <h3>{steps[stage].title}</h3><p>{steps[stage].description}.</p>
              </section>
              <section className="demo-note-card"><span className="demo-eyebrow">REAL SYSTEM VS DEMO</span><h3>Built beyond the mockup.</h3><p>Di aplikasi sebenarnya, otorisasi staf, stok, pembayaran, struk, dan peminjaman game diproses oleh Spring Boot dan PostgreSQL, bukan oleh simulasi ini.</p><Link href="/staff/login">Portal staf lokal <span aria-hidden="true">↗</span></Link></section>
            </aside>
          </div>
          <footer className="demo-workspace-footer">SIMULATED INTERACTION / NO PERSISTENT DATA <span>SMART CAFE SYSTEM © NATSX</span></footer>
        </div>
      </div>
    </main>
  );
}
