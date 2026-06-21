-- finished_on: epoch day on which a recurring routine was finished/retired from the client.
-- Null = active. The routine stops appearing on days after this date (client-side firesOn cutoff);
-- days up to and including it keep their per-day completion history.
ALTER TABLE tasks ADD COLUMN finished_on BIGINT;
