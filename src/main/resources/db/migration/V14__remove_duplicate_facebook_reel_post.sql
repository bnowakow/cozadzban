-- Remove a duplicate Facebook import where the same reel was later saved as a profile post.
-- Keep the earlier canonical reel URL and delete the later profile-post alias.

DELETE FROM article_content
 WHERE article_id IN (
     SELECT duplicate.id
       FROM article duplicate
       JOIN article canonical
         ON canonical.url = 'https://www.facebook.com/reel/2758125771253657/'
      WHERE duplicate.url = 'https://www.facebook.com/bartek.dobrowolski.nowakowski/posts/pfbid0t84F3bzWBM86PhPcsmXnXQhmx1pXa7X2wKFZC46897JSDhdqiUBH5fHuT5HZ1ZjTl'
        AND duplicate.published_at = canonical.published_at
 );

DELETE FROM article
 WHERE id IN (
     SELECT duplicate.id
       FROM article duplicate
       JOIN article canonical
         ON canonical.url = 'https://www.facebook.com/reel/2758125771253657/'
      WHERE duplicate.url = 'https://www.facebook.com/bartek.dobrowolski.nowakowski/posts/pfbid0t84F3bzWBM86PhPcsmXnXQhmx1pXa7X2wKFZC46897JSDhdqiUBH5fHuT5HZ1ZjTl'
        AND duplicate.published_at = canonical.published_at
 );
