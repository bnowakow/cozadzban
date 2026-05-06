-- Phase 17 / Item 47: add nullable published_at to article table
-- and an index for filtering/sorting.

ALTER TABLE article
    ADD COLUMN published_at TIMESTAMPTZ;

CREATE INDEX article_published_at_idx ON article (published_at DESC);
