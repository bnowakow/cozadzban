ALTER TABLE source_thumbnail_delivery_check
    ADD COLUMN cache_available BOOLEAN NOT NULL DEFAULT FALSE;
