"use client";

export function PrintButton() {
  return (
    <button type="button" className="guest-primary" onClick={() => window.print()}>
      Cetak / simpan PDF
    </button>
  );
}
