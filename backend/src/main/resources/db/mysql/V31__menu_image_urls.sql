-- MySQL 8: only approved Cloudinary image URLs may be stored by API.
-- Existing financial and receipt evidence is intentionally unchanged.
ALTER TABLE menu_items ADD COLUMN image_url VARCHAR(700);
