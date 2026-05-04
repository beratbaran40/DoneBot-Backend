-- V10: optional location attached to a task. All four columns nullable; if a chat-set
-- task only has an address string ("Kadıköy"), the lat/lng stay NULL until the user
-- refines the pin in the Android app's location picker. Tap-to-open uses geo:?q=
-- which Maps resolves on its own when coordinates are missing.
--
-- DECIMAL(9,6) gives ~10 cm precision and matches the Android client's Double-as-cents
-- expectation. VARCHAR(120) is enough for a label, VARCHAR(500) for a full address line.

ALTER TABLE tasks ADD COLUMN location_lat DECIMAL(9,6);
ALTER TABLE tasks ADD COLUMN location_lng DECIMAL(9,6);
ALTER TABLE tasks ADD COLUMN location_name VARCHAR(120);
ALTER TABLE tasks ADD COLUMN location_address VARCHAR(500);
