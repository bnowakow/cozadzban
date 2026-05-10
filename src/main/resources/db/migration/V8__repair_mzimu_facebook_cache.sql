-- The mzimu post was captured through the unavailable-post fallback. Do not keep
-- the fallback label as article content. If an operator has already pasted the
-- real post text into the cache, align the article title with that text.

UPDATE article a
SET title = CASE
    WHEN length(regexp_replace(c.content, '\s+', ' ', 'g')) <= 120
        THEN trim(regexp_replace(c.content, '\s+', ' ', 'g'))
    ELSE trim(left(regexp_replace(c.content, '\s+', ' ', 'g'), 120)) || '...'
END
FROM article_content c
WHERE c.article_id = a.id
  AND lower(a.url) = 'https://www.facebook.com/mzimu/posts/pfbid02ouruuuruof5knkqjiyyyvdgkwgqrwsweja7tmf1tw9xzzbnp8dd3yth6lxntgru7l'
  AND c.content IS NOT NULL
  AND trim(c.content) <> ''
  AND trim(c.content) <> 'Facebook post by mzimu';

DELETE FROM article_content c
USING article a
WHERE c.article_id = a.id
  AND lower(a.url) = 'https://www.facebook.com/mzimu/posts/pfbid02ouruuuruof5knkqjiyyyvdgkwgqrwsweja7tmf1tw9xzzbnp8dd3yth6lxntgru7l'
  AND trim(c.content) = 'Facebook post by mzimu';
