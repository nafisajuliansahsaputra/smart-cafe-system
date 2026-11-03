# M7: Real catalog photo links (Flyway V31)

## Actual implementation

The original logical data-model notes mentioned `image_key` but live
Flyway V1–V30 contained **no** image column. V31 adds a nullable
`menu_items.image_url varchar(700)`, with zero changes to payment,
order or receipt records. Existing products have a null image and
render normally as text-first menu cards.

Only **MANAGER** and **ADMIN** may add, change or clear the photo link
via the existing audited catalog create/edit API and the real
`/staff/catalog` UI. New items remain `HIDDEN` until explicitly
published. Item edits use `expectedRevision`; old clients that omit
`imageUrl` preserve the previous link, and an explicit empty
string clears it. This does not modify stock, variants, totals or
historical snapshots.

## CDN security / deployment configuration

Set **only** `SCS_MENU_IMAGE_CLOUD_NAME=<approved-cloud-name>`
on the API environment. This is a **public Cloudinary account name**,
not an API key. No Cloudinary account, credentials or file-upload
permission is assumed. Empty (default) disables assigning nonempty
photo URLs while permitting ordinary menu create/edit.

The backend accepts *only* URLs of this shape:
`https://res.cloudinary.com/<approved-cloud-name>/image/upload/v1234/menu/photo.webp`
(with version optionally omitted). Allowed file extensions:
`.jpg`, `.jpeg`, `.png`, `.webp`, `.avif`.
External domain, port, credentials, query, fragment, escaped path,
`image/fetch`, vector/SVG, PDF or non-HTTPS are rejected. The
Cloudinary tenant segment must exactly match the configured account.
Validation happens before the SQL write, and PostgreSQL stores just
the approved URL. Frontend independently validates the origin/path
before rendering the link. No server-side URL fetch or arbitrary
image optimization proxy exists.

Operators upload images to their **own approved Cloudinary account**
using their existing provider dashboard, then paste the secure
uploaded-image delivery URL into the item editor. This scope does
**not** provide direct in-browser file uploads, image deletion from
Cloudinary, paid provider access or unsanctioned third-party tools.
Removing the link does not delete the asset from the provider, and
asset licensing/retention remain the cafe operator's responsibility.

The browser loads image bytes directly from Cloudinary, so the
provider may see the visiting browser's IP. Requests have
`referrerPolicy=no-referrer`; the menu sends no guest proof, cookie,
phone number or payment credential to the CDN. Before a public
deployment, review the privacy notice, CDN contractual terms,
image accessibility/alt text, actual mobile loading and image
availability. Never place secrets, receipt/guest IDs or table tokens
in image URLs.

## Verification

- New `CatalogImagePolicyTest` rejects unapproved domain, local IP,
  spoofed hostname, non-HTTPS, alternate cloud name, remote fetch
  transformation, SVG, encoded paths and token-like query strings.
- `CartAndStockIntegrationTest` exercises real PostgreSQL
  manager-only write/clear, legacy omission semantics, revision
  counter, guest menu and manager listing projections.
- V27→V31 in-place upgrade rehearsal verifies old financial evidence
  and untouched historical data plus nullable new column.
- Real isolated browser manager test stores and removes a synthetic
  Cloudinary URL; image bytes are **not** uploaded/fetched by tests.
  No live Cloudinary account or permanent asset is required.

## Known remaining work

First-party file upload (including authenticated Cloudinary signed
upload), actual asset management, image dimension/format/size
verification, optimized responsive variants and real-device photo
audit are separate work requiring an authorized provider account.
This is operational image *link* management, not an upload service.
