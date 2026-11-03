-- M7: optional external menu-image URL (approved CDN only).
-- All existing menu items remain valid, and historical money/receipts are unchanged.
ALTER TABLE menu_items
  ADD COLUMN image_url varchar(700);

-- The API additionally verifies HTTPS, configured Cloudinary cloud/account and
-- uploaded-image path before persisting; no server-side image fetching.
COMMENT ON COLUMN menu_items.image_url IS
  'Optional approved HTTPS Cloudinary image/upload URL; never an upload or payment token';
