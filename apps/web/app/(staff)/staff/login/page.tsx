import { redirect } from "next/navigation";
import { loginStaff } from "../actions";
import { staffIdentity } from "@/lib/staff-server";

export const dynamic = "force-dynamic";

const messages: Record<string, string> = {
  invalid: "Login atau kata sandi tidak valid.",
  limited: "Terlalu banyak percobaan. Coba lagi nanti.",
  offline: "Layanan autentikasi belum tersedia. Coba lagi setelah API pulih.",
  expired: "Sesi sudah berakhir. Silakan login kembali.",
  "signed-out": "Sesi telah diakhiri dari perangkat ini.",
  "password-changed": "Kata sandi berhasil diganti. Semua sesi lama telah dicabut. Silakan masuk kembali.",
  "revocation-unconfirmed":
    "Sesi di browser dihapus, tetapi pencabutan di server belum terkonfirmasi. Hubungi administrator bila perangkat tidak aman.",
};

export default async function StaffLogin({
  searchParams,
}: {
  searchParams: Promise<{ status?: string }>;
}) {
  const current = await staffIdentity();
  if (current.kind === "signed-in") redirect("/staff");
  const { status } = await searchParams;
  const message = status && Object.hasOwn(messages, status) ? messages[status] : undefined;
  return (
    <main className="staff-shell staff-login">
      <div className="staff-eyebrow">SMART CAFE SYSTEM / STAFF</div>
      <section className="staff-panel">
        <h1>Masuk sebagai staf</h1>
        <p className="staff-muted">
          Login hanya untuk operator terotorisasi. Setiap perintah pembayaran
          dan pesanan diverifikasi oleh server.
        </p>
        {message && <p className="staff-notice" role="status">{message}</p>}
        <form action={loginStaff} className="staff-form">
          <label htmlFor="login">Nama pengguna</label>
          <input id="login" name="login" autoComplete="username" maxLength={120} required />
          <label htmlFor="password">Kata sandi</label>
          <input
            id="password"
            name="password"
            type="password"
            autoComplete="current-password"
            minLength={8}
            maxLength={128}
            required
          />
          <button className="staff-primary" type="submit">Masuk</button>
        </form>
      </section>
      <p className="staff-footnote">Lingkungan pengembangan. Jangan memproses uang atau data pelanggan sungguhan.</p>
    </main>
  );
}
