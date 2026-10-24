-- SPDX-License-Identifier: GPL-3.0-or-later
-- Queue existing articles without a usable source image. Preserve any status
-- already recorded by a newer import or an earlier generation attempt.
INSERT INTO facebook_article_image_generation(article_id, status)
SELECT article.id, 'MISSING_THUMBNAIL'
  FROM article
 WHERE article.thumbnail IS NULL
    OR btrim(article.thumbnail) = ''
    OR btrim(article.thumbnail) !~* '^https?://[^[:space:]/?#]+'
    OR btrim(article.thumbnail) ~* '^https?://(localhost|127[.]0[.]0[.]1|[[]::1[]])([:/?#]|$)'
ON CONFLICT (article_id) DO NOTHING;
