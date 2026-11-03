"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import type { MenuItem } from "@/lib/guest-server";

const rupiah = new Intl.NumberFormat("id-ID", {
  style: "currency", currency: "IDR", maximumFractionDigits: 0,
});

/**
 * Client-only discovery state over a server-validated public catalog snapshot.
 * No guest token, stock mutation, checkout logic, or localStorage is involved.
 */
export default function GuestMenuBrowser({ items }: { items: MenuItem[] }) {
  const [query, setQuery] = useState("");
  const [activeCategory, setActiveCategory] = useState<string | null>(null);
  const categories = useMemo(() =>
    Array.from(new Set(items.map((item) => item.category))), [items]);
  const filtered = useMemo(() => {
    const words = query.trim().toLocaleLowerCase("id-ID").split(/\s+/).filter(Boolean);
    return items.filter((item) => {
      if (activeCategory !== null && item.category !== activeCategory) return false;
      const haystack = [item.name, item.category, item.description ?? ""]
        .join(" ").toLocaleLowerCase("id-ID");
      return words.every((word) => haystack.includes(word));
    });
  }, [items, query, activeCategory]);
  const shownCategories = categories.filter((category) =>
    filtered.some((item) => item.category === category));

  return (
    <div className="guest-menu-browser">
      <section className="guest-menu-discovery" aria-label="Cari dan saring menu">
        <div className="guest-menu-search">
          <label htmlFor="guest-menu-search">Cari makanan atau minuman</label>
          <div className="guest-menu-search-controls">
            <input id="guest-menu-search" type="search" value={query}
              onChange={(event) => setQuery(event.target.value)}
              maxLength={80} autoComplete="off"
              placeholder="Cari nama, kategori, atau deskripsi..." />
            {query && <button className="guest-secondary" type="button"
              onClick={() => setQuery("")}>Hapus pencarian</button>}
          </div>
        </div>
        <div className="guest-category-tabs" role="group" aria-label="Filter kategori menu">
          <button type="button" className="guest-category-filter"
            aria-pressed={activeCategory === null}
            onClick={() => setActiveCategory(null)}>
            Semua menu
          </button>
          {categories.map((category) => <button key={category} type="button"
            className="guest-category-filter"
            aria-pressed={activeCategory === category}
            onClick={() => setActiveCategory(category)}>
            {category}
          </button>)}
        </div>
        <p className="guest-menu-count" role="status" aria-live="polite">
          {filtered.length} dari {items.length} menu tersedia
          {activeCategory !== null ? ` · ${activeCategory}` : ""}
        </p>
      </section>

      {filtered.length === 0 ? (
        <section className="guest-panel guest-menu-empty" role="status">
          <h2>Tidak ada menu yang cocok</h2>
          <p>Coba kata kunci lain atau tampilkan semua kategori. Hanya menu yang
            dapat dipesan berdasarkan stok server yang ditampilkan.</p>
          <button className="guest-secondary" type="button" onClick={() => {
            setQuery("");
            setActiveCategory(null);
          }}>Tampilkan semua menu</button>
        </section>
      ) : shownCategories.map((category) => (
        <section key={category} className="guest-category">
          <h2>{category}</h2>
          <div className="guest-menu-grid">
            {filtered.filter((item) => item.category === category).map((item) => (
              <article className="guest-panel guest-menu-card" key={item.id}>
                <div>
                  <h3>{item.name}</h3>
                  {item.description &&
                    <p className="guest-menu-description">{item.description}</p>}
                  <p className="guest-price">{rupiah.format(item.priceIdr)}
                    {" "}<span>harga mulai</span>
                  </p>
                  {item.remainingQuantity !== null &&
                    <small>Sisa {item.remainingQuantity} porsi</small>}
                </div>
                <Link className="guest-secondary" href={`/visit/menu/${item.id}`}>
                  Pilih menu
                </Link>
              </article>
            ))}
          </div>
        </section>
      ))}
    </div>
  );
}
