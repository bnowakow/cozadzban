-- The Facebook post linked through bit.ly; the redirect points to this Economist article.
UPDATE article
SET url = 'https://www.economist.com/united-states/2026/11/13/the-jensen-huang-scandal-reaches-donald-trumps-cabinet',
    title = 'The Jensen Huang scandal reaches Donald Trump’s cabinet',
    lead = 'Howard Lutnick, the commerce secretary, is in the firing line.'
WHERE id = 763 AND url = 'http://bit.ly/4iQcwQ3';

UPDATE article_content
SET content = 'Howard Lutnick, the commerce secretary, is in the firing line.',
    truncated = false,
    captured_at = now()
WHERE article_id = 763
  AND EXISTS (SELECT 1 FROM article WHERE id = 763 AND url = 'https://www.economist.com/united-states/2026/11/13/the-jensen-huang-scandal-reaches-donald-trumps-cabinet');

UPDATE facebook_article_proposal
SET canonical_article_url = 'https://www.economist.com/united-states/2026/11/13/the-jensen-huang-scandal-reaches-donald-trumps-cabinet'
WHERE id = 552 AND article_id = 763 AND canonical_article_url = 'http://bit.ly/4iQcwQ3';
