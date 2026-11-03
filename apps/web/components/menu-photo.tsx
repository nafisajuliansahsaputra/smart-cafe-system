import { isMenuImageUrl } from "@/lib/menu-images";

/**
 * Display only a manager-approved remote asset. No server proxy, transformations,
 * or browser secrets; external requests omit the visit/staff URL as Referer.
 */
export function MenuPhoto({ src, name, className = "" }: {
  src: string | null; name: string; className?: string;
}) {
  if (!isMenuImageUrl(src)) return null;
  return (
    // eslint-disable-next-line @next/next/no-img-element
    <img className={className ? "menu-photo " + className : "menu-photo"}
      src={src} alt={`Foto ${name}`} width={720} height={540}
      loading="lazy" decoding="async" referrerPolicy="no-referrer" />
  );
}
