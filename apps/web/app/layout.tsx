import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Smart Cafe System",
  description: "Fondasi aplikasi pemesanan dan operasional kafe.",
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
