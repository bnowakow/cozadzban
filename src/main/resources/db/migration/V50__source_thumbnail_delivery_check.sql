CREATE TABLE source_thumbnail_delivery_check (
    article_id BIGINT PRIMARY KEY REFERENCES article(id) ON DELETE CASCADE,
    thumbnail_url TEXT NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('AVAILABLE', 'UNAVAILABLE')),
    checked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error TEXT
);
