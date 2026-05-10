-- The mzimu Facebook post is unavailable to logged-out crawlers, so an existing
-- cache row for this URL can be a linked article body rather than the post text.
-- Drop the stale cache; admins can paste the real post body through the content
-- cache dialog, which also retitles Facebook posts from that pasted text.

DELETE FROM article_content c
USING article a
WHERE c.article_id = a.id
  AND lower(a.url) = 'https://www.facebook.com/mzimu/posts/pfbid02ouruuuruof5knkqjiyyyvdgkwgqrwsweja7tmf1tw9xzzbnp8dd3yth6lxntgru7l';
