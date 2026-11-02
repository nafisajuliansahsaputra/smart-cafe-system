import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Smart Cafe System | QR Ordering & Cafe Operations by NATSX",
  description:
    "Portfolio full-stack Smart Cafe System: ordering via QR meja, kasir, dapur, waiter, serta demo interaktif tanpa transaksi nyata.",
  openGraph: {
    title: "Smart Cafe System | QR Ordering & Cafe Operations",
    description:
      "Jelajahi sistem pemesanan dan operasional kafe melalui simulasi interaktif tanpa akun, uang, atau data pribadi.",
    type: "website",
    locale: "id_ID",
  },
};

export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="id">
      <body>{children}</body>
    </html>
  );
}
