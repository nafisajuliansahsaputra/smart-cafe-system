/**
 * Browser-side defense in depth for menu photos; no arbitrary URL, redirects,
 * Cloudinary fetch transformation, SVG or script-capable media in an <img>.
 * The backend additionally restricts the exact Cloudinary tenant at write time.
 */
export function isMenuImageUrl(value: unknown): value is string {
  if (typeof value !== "string" || value.length > 700 ||
    value.includes("%") || value.includes("\\") ||
    /[\x00-\x20\x7f]/.test(value)) return false;
  try {
    const url = new URL(value);
    return url.protocol === "https:" && url.hostname === "res.cloudinary.com" &&
      url.port === "" && !url.username && !url.password &&
      !url.search && !url.hash &&
      /^\/[a-z0-9][a-z0-9_-]{1,62}\/image\/upload\/(?:v[0-9]{1,12}\/)?[A-Za-z0-9][A-Za-z0-9/_-]{0,350}\.(jpg|jpeg|png|webp|avif)$/.test(url.pathname) &&
      !url.pathname.includes("//") && !url.pathname.includes("..") &&
      url.href === value;
  } catch {
    return false;
  }
}
