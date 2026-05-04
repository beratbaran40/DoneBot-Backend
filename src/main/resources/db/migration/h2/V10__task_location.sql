-- V10: optional location attached to a task. All four columns nullable; if a chat-set
-- task only has an address string ("Kadıköy"), the lat/lng stay NULL until the user
-- refines the pin in the Android app's location picker. Tap-to-open uses geo:?q=
-- which Maps resolves on its own when coordinates are missing.

ALTER TABLE tasks ADD COLUMN location_lat DECIMAL(9,6);
ALTER TABLE tasks ADD COLUMN location_lng DECIMAL(9,6);
ALTER TABLE tasks ADD COLUMN location_name VARCHAR(120);
ALTER TABLE tasks ADD COLUMN location_address VARCHAR(500);
