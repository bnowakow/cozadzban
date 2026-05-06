-- Phase 20: preserved article content cache
-- Stores plain-text content fetched at article creation time.
-- Content is internal-only and never exposed publicly.
-- Max enforced size: 5 MB (5_242_880 bytes).

CREATE TABLE article_content (
    article_id   BIGINT      NOT NULL PRIMARY KEY REFERENCES article(id) ON DELETE CASCADE,
    content      TEXT        NOT NULL,
    truncated    BOOLEAN     NOT NULL DEFAULT FALSE,
    captured_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
