-- Records that Meta refused the article's own thumbnail for its Facebook Page post, so the AI
-- illustration exists only for Facebook while the website keeps showing the source thumbnail.
ALTER TABLE facebook_article_image_generation
    ADD COLUMN source_thumbnail_rejected BOOLEAN NOT NULL DEFAULT FALSE;

-- Existing jobs: an article with its own thumbnail whose Page post used (or still waits for) an
-- AI illustration got there because Meta rejected that thumbnail.
UPDATE facebook_article_image_generation job
SET source_thumbnail_rejected = TRUE
FROM article a
WHERE a.id = job.article_id
  AND a.thumbnail ~* '^\s*https?://[^/\s]+'
  AND a.thumbnail !~* '^\s*https?://(localhost|127\.0\.0\.1|\[::1\])([:/]|$)'
  AND EXISTS (
      SELECT 1 FROM facebook_page_publication p
      WHERE p.article_id = a.id
        AND (p.image_source = 'CODEX_GENERATED' OR (p.status = 'PENDING' AND p.attempt_count > 0))
  );
